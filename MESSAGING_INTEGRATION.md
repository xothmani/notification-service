# Messaging Service Integration

**Service:** notification-service  
**Version:** 0.0.1-SNAPSHOT  
**Stack:** Spring Boot 3.5.13 · Java 21 · Redis Streams · Spring Cloud OpenFeign  

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture Flow](#2-architecture-flow)
3. [Files Changed / Created](#3-files-changed--created)
4. [Notification Payload Schema](#4-notification-payload-schema)
5. [How Email Delivery Works](#5-how-email-delivery-works)
6. [How to Test](#6-how-to-test)

---

## 1. Overview

### What Was Built

The notification-service was extended to deliver outbound **EMAIL** and **SMS** notifications through an external messaging service. Prior to this integration, the service only supported in-app (SSE) delivery. Producers can now include `EMAIL` and/or `SMS` in the `channels` array of a Redis Stream payload, along with per-recipient email addresses and phone numbers, and the worker will dispatch each message through the messaging service automatically.

### Why

Survey Teams and Team Boost require email confirmations, alerts, and SMS fallbacks to be sent as part of the same notification event that triggers in-app delivery. Centralising dispatch inside the notification worker ensures that:

- All channels are driven from a single payload published to the stream.
- Delivery failures on any external channel are isolated and never cause a MongoDB retry or duplicate notification save.
- The messaging service handles provider-level concerns (SendGrid, Twilio) and the notification-service remains agnostic of them.

### External Services Used

| Service | Role | Protocol |
|---|---|---|
| **messaging-service** | Accepts async send requests for EMAIL and SMS | HTTP POST (Feign) |
| **SendGrid** | Delivers outbound email (managed by messaging-service) | SMTP / SendGrid API |
| **Twilio** | Delivers outbound SMS — stub only, not yet active | SMS API |
| **Redis** | Stream transport (`notifications_stream`) and DLQ | Redis Streams / List |
| **MongoDB** | Persistent notification storage | MongoDB driver |

---

## 2. Architecture Flow

```
Producer (any service)
        │
        │  XADD notifications_stream * payload '{...}'
        ▼
┌───────────────────────┐
│   Redis Stream        │  notifications_stream
│   Consumer Group:     │  notification-group
│   notification-group  │
└───────────┬───────────┘
            │  XREADGROUP (poll every 100 ms)
            ▼
┌───────────────────────────────────────────────────────────────┐
│  NotificationWorker.onMessage()                               │
│                                                               │
│  1. Deserialize payload JSON                                  │
│  2. Validate with Jakarta Bean Validation                     │
│  3. For each recipientId → processForRecipient()             │
│     a. Save Notification to MongoDB        ← CRITICAL        │
│     b. ─── best-effort block (never retries MongoDB) ──────  │
│        · CacheService.invalidateUserCache()                   │
│        · MongoTemplate $inc on unread counter                 │
│        · SseService.pushNotification()   [if IN_APP]         │
│        · MessagingDeliveryService.sendEmail()  [if EMAIL]    │
│        · MessagingDeliveryService.sendSms()    [if SMS]      │
│  4. XACK on success                                           │
└───────────────────────┬───────────────────────────────────────┘
                        │  sendEmail(to, subject, body, correlationId)
                        ▼
            ┌───────────────────────┐
            │  MessagingDelivery    │
            │  Service              │
            │  · builds request map │
            │  · never throws       │
            └───────────┬───────────┘
                        │  POST /api/messages/send-async
                        │  Header: X-HS-Gateway-Verified: <shared-secret>
                        ▼
            ┌───────────────────────┐
            │  MessagingService     │
            │  Client (Feign)       │
            └───────────┬───────────┘
                        │  HTTP POST
                        ▼
            ┌───────────────────────┐
            │  messaging-service    │
            │  (external container) │
            └───────────┬───────────┘
                        │
                        ▼
            ┌───────────────────────┐
            │  SendGrid API         │
            └───────────┬───────────┘
                        │
                        ▼
                  User's Email Inbox
```

**Failure isolation:** If `MessagingDeliveryService.sendEmail()` throws, the exception is caught inside the best-effort `try/catch` in `processForRecipient()` (lines 230–262 of `NotificationWorker.java`). The MongoDB save has already completed at that point. The exception is logged as WARN and processing continues. The stream message is still acknowledged on success of the MongoDB save, so no duplicate notification is created on the next delivery attempt.

---

## 3. Files Changed / Created

### 3.1 `src/main/java/…/dto/NotificationPayload.java` — **Modified**

**What it does:** Data Transfer Object that represents a single notification event published to the Redis Stream. Deserialised from the `payload` field of each stream record.

**Change:** Two new nullable fields added at lines 71–76.

| Field | JSON key | Type | Purpose |
|---|---|---|---|
| `recipientEmails` | `recipient_emails` | `List<String>` | Parallel list to `recipient_ids`. Index `i` holds the email address for `recipientIds[i]`. Nullable — omit when EMAIL channel is not used. |
| `recipientPhones` | `recipient_phones` | `List<String>` | Parallel list to `recipient_ids`. Index `i` holds the E.164 phone number for `recipientIds[i]`. Nullable — omit when SMS channel is not used. |

Neither field carries validation constraints. A missing or `null` list is treated as "no address available for this channel" and the channel delivery is silently skipped for that recipient.

---

### 3.2 `src/main/java/…/client/MessagingServiceClient.java` — **New**

**What it does:** Spring Cloud OpenFeign declarative HTTP client. Translates a Java method call into an HTTP POST to the messaging-service. Spring auto-generates the implementation at startup via `@EnableFeignClients` on the application class.

**Key method:**

```java
// Line 13
@PostMapping("/api/messages/send-async")
void sendAsync(
    @RequestHeader("X-HS-Gateway-Verified") String secret,
    @RequestBody Map<String, Object> request);
```

- The `void` return type means Feign throws `FeignException` on any non-2xx response. The caller (`MessagingDeliveryService`) catches all exceptions.
- The `X-HS-Gateway-Verified` header carries the shared secret used by the messaging-service to authenticate internal service-to-service calls.
- The base URL is injected from `${messaging.service.url}` at startup.

---

### 3.3 `src/main/java/…/service/MessagingDeliveryService.java` — **New**

**What it does:** Anti-corruption layer between `NotificationWorker` and `MessagingServiceClient`. Ensures the worker never receives an exception from a messaging failure. Constructs the request payload in the format expected by the messaging-service API.

**Key methods:**

```java
// Line 25
public boolean sendEmail(String to, String subject, String body, String correlationId)
```
Builds a `Map<String, Object>` with the fields below, calls `client.sendAsync()`, and returns `true` on success or `false` on any exception. Never propagates.

Request body fields sent to the messaging-service:

| Field | Value |
|---|---|
| `channel` | `"EMAIL"` |
| `to` | recipient email address |
| `subject` | notification title |
| `body` | notification description |
| `html` | `false` |
| `sourceService` | `"notification-service"` |
| `correlationId` | MongoDB notification document ID |

```java
// Line 45
public boolean sendSms(String phoneNumber, String body, String correlationId)
```
SMS stub — logs a skip message and returns `false`. Implementation pending Twilio spec.

---

### 3.4 `src/main/java/…/worker/NotificationWorker.java` — **Modified**

**What it does:** Redis Stream consumer. Deserialises, validates, and persists each notification, then dispatches to all requested channels.

**Changes:**

- `MessagingDeliveryService messagingDeliveryService` added as the last constructor parameter (line 72).
- `processForRecipient()` extended (lines 217–258) with per-recipient email/phone resolution and channel dispatch.

**Key logic added to `processForRecipient()` (lines 217–258):**

```java
int recipientIndex = payload.getRecipientIds().indexOf(recipientId);   // line 217

List<String> emails = payload.getRecipientEmails();                    // line 219
String email = (emails != null
    && recipientIndex >= 0
    && recipientIndex < emails.size())
    ? emails.get(recipientIndex) : null;                               // line 220–221

List<String> phones = payload.getRecipientPhones();                    // line 223
String phone = (phones != null
    && recipientIndex >= 0
    && recipientIndex < phones.size())
    ? phones.get(recipientIndex) : null;                               // line 224–225

// Inside best-effort try block:
if (channels.contains("EMAIL") && email != null) {
    messagingDeliveryService.sendEmail(                                // line 249
        email, payload.getTitle(), payload.getDescription(),
        notification.getId());
}

if (channels.contains("SMS") && phone != null) {
    messagingDeliveryService.sendSms(                                  // line 254
        phone,
        payload.getTitle() + ": " + payload.getDescription(),
        notification.getId());
}
```

The index lookup (`indexOf`) maps each `recipientId` back to its position in `recipient_ids` so the correct parallel email/phone entry is selected.

---

### 3.5 `src/main/java/…/config/RedisStreamConfig.java` — **Modified**

**What it does:** Configures the `StreamMessageListenerContainer` that polls `notifications_stream` and routes records to `NotificationWorker`.

**Current state:** Uses the Spring Boot auto-configured `LettuceConnectionFactory` (pooled, `min-idle=2`). The pool establishes connections eagerly at context startup when Docker DNS is fully operational. The `xReadGroup` dedicated connection is borrowed from this pre-resolved pool, avoiding any runtime DNS resolution on Netty I/O threads.

`pollTimeout` is set to `100 ms` — well under the 2 s command timeout on the auto-configured factory — so no `QueryTimeoutException` can occur.

---

### 3.6 `src/main/resources/application.properties` — **Modified**

**New properties added:**

```properties
# Messaging service — used by MessagingDeliveryService for EMAIL/SMS delivery
messaging.service.url=${MESSAGING_SERVICE_URL:http://localhost:8080}
messaging.service.shared-secret=${PUBLIC_API_SHARED_SECRET:dev-shared-secret-change-me}
```

| Property | Environment Variable | Default (local) | Purpose |
|---|---|---|---|
| `messaging.service.url` | `MESSAGING_SERVICE_URL` | `http://localhost:8080` | Base URL injected into `MessagingServiceClient` via `@FeignClient(url=…)` |
| `messaging.service.shared-secret` | `PUBLIC_API_SHARED_SECRET` | `dev-shared-secret-change-me` | Shared secret sent as `X-HS-Gateway-Verified` header on every request |

**Set in `docker-compose.yml`:**
```yaml
MESSAGING_SERVICE_URL: http://messaging-service:8080
PUBLIC_API_SHARED_SECRET: ${PUBLIC_API_SHARED_SECRET}
```

---

### 3.7 `pom.xml` — **Modified**

**New BOM in `dependencyManagement`:**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>2025.0.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

**New runtime dependency:**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

Spring Cloud 2025.0.0 is the release train aligned with Spring Boot 3.5.x. No explicit version is needed on `spring-cloud-starter-openfeign` because it is managed by the BOM.

`@EnableFeignClients` was added to `NotificationServiceApplication.java` to activate Feign client scanning at startup.

---

## 4. Notification Payload Schema

The full JSON object published to `notifications_stream` via `XADD`:

```json
{
  "recipient_ids":    ["user-abc123", "user-def456"],
  "recipient_emails": ["alice@example.com", "bob@example.com"],
  "recipient_phones": ["+12025551234", "+12025555678"],
  "organization_id":  "org-xyz789",
  "tier":             "HIGH",
  "type":             "ALERT",
  "title":            "Your report is ready",
  "description":      "The Q2 survey report has been generated and is available for download.",
  "redirect_uri":     "https://app.surveyteams.com/reports/q2-2026",
  "image_url":        "https://cdn.surveyteams.com/icons/report.png",
  "channels":         ["IN_APP", "EMAIL"],
  "metadata": {
    "reportId": "rpt-001",
    "generatedBy": "survey-service"
  }
}
```

### Field Reference

| Field | Type | Required | Validation |
|---|---|---|---|
| `recipient_ids` | `String[]` | Yes | Non-empty; each entry matches `^[a-zA-Z0-9_-]+$` |
| `recipient_emails` | `String[]` | No | Parallel to `recipient_ids`; null/omit when EMAIL not used |
| `recipient_phones` | `String[]` | No | Parallel to `recipient_ids`; null/omit when SMS not used |
| `organization_id` | `String` | Yes | Non-blank |
| `tier` | `String` | Yes | Matches `^[A-Z_]+$` (e.g. `HIGH`, `NORMAL`, `LOW`) |
| `type` | `String` | Yes | Matches `^[A-Z_]+$` (e.g. `ALERT`, `INFO`, `REMINDER`) |
| `title` | `String` | Yes | Non-blank, max 500 characters |
| `description` | `String` | Yes | Non-blank, max 2 000 characters |
| `redirect_uri` | `String` | Yes | Non-blank, max 200 characters, must start with `http://`, `https://`, or `/` |
| `image_url` | `String` | No | Optional image URL |
| `channels` | `String[]` | Yes | Non-empty; each entry one of `IN_APP`, `EMAIL`, `SMS`, `PUSH` |
| `metadata` | `Object` | No | Arbitrary key-value map; stored as-is in MongoDB |

---

## 5. How Email Delivery Works

### Step-by-Step Execution

**Step 1 — Stream poll**  
`StreamPollTask.run()` (spring-data-redis) issues an `XREADGROUP` command every 100 ms. On a new record, it calls `NotificationWorker.onMessage()` (line 88).

**Step 2 — Deserialisation**  
`onMessage()` extracts the `payload` field from the stream record (line 92) and deserialises it with `objectMapper.readValue(payloadJson, NotificationPayload.class)` (line 102). A failure here routes the record to the DLQ (line 105) and issues XACK.

**Step 3 — Validation**  
`validator.validate(payload)` (line 110) runs all Jakarta Bean Validation constraints. Any violation sends the record to the DLQ (line 116) and issues XACK.

**Step 4 — Retry loop entry**  
`processWithRetry(payload, broadcastId)` (line 127) is called with a freshly generated `broadcastId` (UUID). The loop retries up to `MAX_RETRIES = 3` times with exponential back-off (1 s → 2 s → 4 s) on MongoDB failure.

**Step 5 — MongoDB save**  
Inside `processForRecipient()` (line 195), a `Notification` document is built and saved with `notificationRepository.save(notification)` (line 214). Any exception from this line propagates up to the retry loop. **This is the only operation that triggers retries.**

**Step 6 — Email address resolution**  
After the save succeeds (line 217):

```java
int recipientIndex = payload.getRecipientIds().indexOf(recipientId);
String email = (emails != null && recipientIndex >= 0 && recipientIndex < emails.size())
        ? emails.get(recipientIndex) : null;
```

If `recipient_emails` was not provided, or the index is out of bounds, `email` is `null` and the EMAIL channel is skipped silently.

**Step 7 — Channel dispatch (best-effort)**  
Inside the `try` block at line 230, after cache invalidation and SSE delivery, the email check runs at line 248:

```java
if (channels != null && channels.contains("EMAIL") && email != null) {
    messagingDeliveryService.sendEmail(
            email, payload.getTitle(), payload.getDescription(), notification.getId());
}
```

`notification.getId()` is the MongoDB-assigned document ID and is used as the `correlationId` in the messaging-service request, allowing cross-service tracing.

**Step 8 — MessagingDeliveryService**  
`sendEmail()` (line 25 of `MessagingDeliveryService.java`) builds the request map and calls `client.sendAsync(secret, request)` (line 36). On success it logs INFO and returns `true`. On any exception it logs ERROR and returns `false`. **It never re-throws.**

**Step 9 — Feign HTTP call**  
`MessagingServiceClient.sendAsync()` (line 14 of `MessagingServiceClient.java`) executes:

```
POST http://messaging-service:8080/api/messages/send-async
X-HS-Gateway-Verified: <shared-secret>
Content-Type: application/json

{
  "channel": "EMAIL",
  "to": "alice@example.com",
  "subject": "Your report is ready",
  "body": "The Q2 survey report has been generated...",
  "html": false,
  "sourceService": "notification-service",
  "correlationId": "<mongodb-notification-id>"
}
```

**Step 10 — XACK**  
Back in `onMessage()`, if `processWithRetry()` returns `true`, `ack()` (line 134) calls `XACK notifications_stream notification-group <record-id>`. The message is removed from the pending entries list.

---

## 6. How to Test

### Prerequisites

- Redis is reachable on `localhost:6379`
- The notification-service is running (locally or in Docker)
- The consumer group `notification-group` exists on `notifications_stream` (created automatically at startup)

### Send a Test EMAIL Notification via Redis CLI

```bash
redis-cli XADD notifications_stream '*' payload '{
  "recipient_ids": ["test-user-001"],
  "recipient_emails": ["your-email@example.com"],
  "organization_id": "org-test",
  "tier": "HIGH",
  "type": "ALERT",
  "title": "Test Email Notification",
  "description": "This is a test notification sent via Redis Stream to verify EMAIL delivery.",
  "redirect_uri": "https://example.com",
  "channels": ["IN_APP", "EMAIL"]
}'
```

### Single-line version (copy-paste ready)

```bash
redis-cli XADD notifications_stream '*' payload '{"recipient_ids":["test-user-001"],"recipient_emails":["your-email@example.com"],"organization_id":"org-test","tier":"HIGH","type":"ALERT","title":"Test Email Notification","description":"This is a test notification sent via Redis Stream to verify EMAIL delivery.","redirect_uri":"https://example.com","channels":["IN_APP","EMAIL"]}'
```

### Verify the Record Was Processed

```bash
# Check pending entries — should be empty after successful processing
redis-cli XPENDING notifications_stream notification-group - + 10

# Check the stream length
redis-cli XLEN notifications_stream

# Tail the notification-service logs (Docker)
docker logs -f notification-service
```

### Expected Log Output on Success

```
INFO  NotificationWorker   : Processing message 1234567890-0 from stream notifications_stream
INFO  NotificationWorker   : Successfully processed broadcastId <uuid> for 1 recipient(s)
INFO  MessagingDeliveryService : Email sent to your-email@example.com (correlationId=<mongo-id>)
```

### Test SMS Channel (stub — no delivery)

```bash
redis-cli XADD notifications_stream '*' payload '{"recipient_ids":["test-user-001"],"recipient_phones":["+12025551234"],"organization_id":"org-test","tier":"NORMAL","type":"INFO","title":"SMS Test","description":"Testing SMS stub.","redirect_uri":"https://example.com","channels":["SMS"]}'
```

Expected log: `SMS not yet available — skipping SMS to +12025551234`

---

*This document reflects the implementation as of the `feature/notification-service` branch.*
