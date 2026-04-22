# Notification Flow

---

## HAPPY PATH — Redis stream → MongoDB → SSE → user sees notification

---

### STEP 1 — Caller publishes to the Redis stream

**`InternalNotificationController.java` · `broadcast()`**

- **L35** — `@Valid @RequestBody` — Spring validates the full payload before this method runs
- **L41** — calls `NotificationProducer.publish(payload)`
- **L43** — returns **HTTP 202 Accepted** (queued, not yet delivered)

**`NotificationProducer.java` · `publish()`**

- **L35** — `objectMapper.writeValueAsString(payload)` — serialize payload to a JSON string
- **L36** — if serialization fails → `log.error`, return — message is silently dropped, nothing written to stream
- **L41** — `stringRedisTemplate.opsForStream().add(...)` — write one record to the Redis stream with field `"payload"` = JSON string
- **L44** — `stringRedisTemplate.opsForStream().trim(...)` — cap the stream at 10 000 records so it never grows unbounded
- **L50** — `log.debug(...)` — confirm publish succeeded

---

### STEP 2 — StreamMessageListenerContainer receives the record

**`RedisStreamConfig.java` · `streamContainer()`** *(runs once at startup)*

- **L69** — `createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup)` — create consumer group; `BUSYGROUP` error is caught and ignored
- **L88** — `StreamMessageListenerContainer.create(streamFactory, options)` — uses a dedicated connection factory with a 30 s timeout so `XREADGROUP BLOCK` never races the 2 s main timeout
- **L91** — `container.receive(Consumer.from(group, name), StreamOffset.lastConsumed(), notificationWorker)` — register `NotificationWorker` as the listener
- **L96** — `container.start()` — begin polling in the background

↳ when a record arrives, the JVM calls **`NotificationWorker.onMessage(record)`**

---

### STEP 3 — NotificationWorker.onMessage() entry

**`NotificationWorker.java` · `onMessage()`**

- **L84** — method entry, called by the container on every new record
- **L85** — `record.getStream()` — read which stream this came from
- **L86** — `log.info(...)` — log record ID and stream key

---

### STEP 4 — Payload extraction and validation (three gates)

**Gate 1 — field presence**

- **L88** — `record.getValue().get("payload")` — pull the raw JSON string from the record map
- **L89** — if `null` → `pushToDlq(...)` + `ack(record)` + `return` — bad record is acknowledged and dead-lettered immediately, never retried

**Gate 2 — JSON deserialisation**

- **L98** — `objectMapper.readValue(payloadJson, NotificationPayload.class)` — parse JSON into a `NotificationPayload` object
- **L99** — if parse fails → `pushToDlq(...)` + `ack(record)` + `return`

**Gate 3 — bean validation**

- **L106** — `validator.validate(payload)` — Jakarta Validator checks `@NotNull`, `@NotBlank`, `@Size`, `@Pattern` on every field
- **L107** — if violations exist → `pushToDlq(...)` + `ack(record)` + `return`
- **L118** — `new ArrayList<>(new LinkedHashSet<>(...))` — deduplicate channels, preserve insertion order
- **L121** — `broadcastId = UUID.randomUUID().toString()` — generated **once before the retry loop** so all recipients across all retries share the same ID

---

### STEP 5 — processWithRetry() — up to 3 retries with exponential back-off

**`NotificationWorker.java` · `processWithRetry()`** *(called from `onMessage()` L123)*

- **L143** — `Set<String> succeeded = new HashSet<>()` — track which recipients already saved so they are skipped on retry
- **L144** — `attempt = 0`
- **L146** — `while (attempt <= MAX_RETRIES)` — MAX\_RETRIES = 3, so attempts 0, 1, 2, 3
- **L150** — `if (succeeded.contains(recipientId)) continue` — skip already-saved recipients
- **L153** — `processForRecipient(payload, recipientId, broadcastId)` — see Step 6
- **L155** — catch `DuplicateKeyException` → `succeeded.add(recipientId)` — duplicate means already saved on a prior attempt, treat as success
- **L160** — catch any other `Exception` → `anyFailed = true`
- **L167** — if `!anyFailed` → `return true` — all recipients done, caller will XACK
- **L175** — `delayMs = Math.pow(2, attempt) * 1000` — back-off: 1 000 ms → 2 000 ms → 4 000 ms
- **L176** — `Thread.sleep(delayMs)` — **safe on Java 21 virtual threads**: VT unmounts from carrier, carrier is freed for other work
- **L184** — max retries exhausted → `log.error(...)` + `return false` — caller must **not** XACK; `PendingSweeper` retries

---

### STEP 6 — processForRecipient() saves to MongoDB

**`NotificationWorker.java` · `processForRecipient()`** *(called from `processWithRetry()` L153)*

- **L194–L207** — `Notification.builder()...build()` — assemble the document: `broadcastId`, `recipientId`, `state = "UNSEEN"`, `createdAt = Instant.now()`
- **L210** — `notificationRepository.save(notification)` — **the critical operation**; any exception here propagates to the retry loop in Step 5
- **L212** — `List<String> channels = payload.getChannels()`

---

### STEP 7 — Cache invalidation (best-effort, isolated from retry)

**`NotificationWorker.java` · `processForRecipient()`**

- **L216** — `try {` — Redis failure is caught here and must **not** reach the retry loop; re-running would call `save()` again and create a duplicate notification

**`CacheService.java` · `invalidateUserCache()`** *(called from `processForRecipient()` L217)*

- **L121** — `RedisKeyValidator.validate(userId)` — null / empty / format guard before touching Redis
- **L127** — `redisTemplate.delete("unread_count:{userId}")` — delete the unread count cache entry
- **L128** — catch → `log.warn` — Redis down: warn only, never propagate
- **L134** — `ScanOptions.scanOptions().match("notifications:{userId}:*").count(100).build()` — build a cursor-based scan pattern
- **L137** — `redisTemplate.scan(options)` — **SCAN not KEYS**: non-blocking cursor, safe on production
- **L144** — `redisTemplate.delete(keysToDelete)` — batch-delete all matching notification page caches
- **L145** — catch → `log.warn` — Redis down: cache stays stale until the 5-minute TTL expires

Back in **`processForRecipient()`**:

- **L220** — `mongoTemplate.findAndModify(...new Update().inc("count", 1)...)` — atomic `$inc` on the unread counter in MongoDB; upserts the document if absent. Cache is **invalidated not written back** to avoid a race between two concurrent `$inc` operations leaving a stale cached value.

---

### STEP 8 — SSE push to the user

**`NotificationWorker.java` · `processForRecipient()`**

- **L227** — `if (channels.contains("IN_APP"))`
- **L228** — `sseService.pushNotification(recipientId, notificationService.mapToResponse(notification))`
- **L230** — `sseService.pushUnreadCount(recipientId, notificationService.getUnreadCount(recipientId))`

**`SseService.java` · `send()`** *(called by both push methods via L56)*

- **L59** — `emitters.get(userId)` — look up the open HTTP connection in the `ConcurrentHashMap`
- **L60** — if `null` → `return` silently — user not connected; notification is already in MongoDB, they will see it on next load
- **L62** — `emitter.send(SseEmitter.event().name("new_notification").data(notification))` — push JSON over the open HTTP connection in real time
- **L66–L67** — catch → `log.warn` + `emitters.remove(userId, emitter)` — broken pipe: remove emitter, client reconnects automatically via `EventSource`

↳ `pushUnreadCount()` calls `send()` again with event name `"unread_count_update"` so the badge in the UI updates instantly

---

### STEP 9 — XACK on success

**`NotificationWorker.java` · `onMessage()`**

- **L125** — `if (success) ack(record)` — only called when `processWithRetry()` returns `true`

**`NotificationWorker.java` · `ack()`** *(called from L125)*

- **L132** — `stringRedisTemplate.opsForStream().acknowledge(stream, consumerGroup, recordId)` — XACK removes the record from the pending list; Redis will not deliver it again
- **L136** — catch → `log.warn` — XACK failed: record stays pending, `PendingSweeper` will re-claim and retry; `broadcastId` guard makes retry idempotent

---

## FAILURE FLOWS

---

### FAILURE 1 — Bad payload → DLQ path

**`NotificationWorker.java` · `onMessage()`**

- **L88–L93** — `"payload"` field missing → `pushToDlq(record.getValue(), "Missing payload field")` + `ack(record)`
- **L98–L104** — JSON parse fails → `pushToDlq(record.getValue(), e.getMessage())` + `ack(record)`
- **L106–L115** — bean validation fails → `pushToDlq(record.getValue(), errors)` + `ack(record)`

In all three cases the record is **XACK'd immediately** so one bad message never blocks the queue.

**`NotificationWorker.java` · `pushToDlq()`**

- **L255** — `redisTemplate.opsForList().rightPush("notifications_dlq", rawJob)` — append to the DLQ Redis list
- **L256** — catch (Redis also down)
- **L264** — `failedNotificationRepository.save(failed)` — fallback: write directly to MongoDB `failed_notifications`
- **L270** — catch (Mongo also down) → `log.error("job permanently lost")` — both stores unreachable, log only

**`DlqWorker.java` · `sweepDlq()`** *(@Scheduled every 30 s)*

- **L39** — `redisTemplate.opsForList().leftPop("notifications_dlq")` — pop one job off the front
- **L43** — catch (Redis down) → `log.error`, return — skip this sweep, retry next tick
- **L45** — if `null` → break — queue empty, stop
- **L58** — `failedNotificationRepository.save(...)` — persist to MongoDB `failed_notifications` for manual inspection
- **L67** — catch (Mongo down) → `log.error`
- **L72** — `redisTemplate.opsForList().rightPush("notifications_dlq", job)` — re-queue to tail so job is not lost; stop sweep until Mongo recovers

---

### FAILURE 2 — MongoDB down → retry → pending → PendingSweeper

**`NotificationWorker.java` · `processForRecipient()`**

- **L210** — `notificationRepository.save(...)` throws → exception propagates to `processWithRetry()` L160

**`NotificationWorker.java` · `processWithRetry()`**

- **L160** — `anyFailed = true`
- **L175–L181** — sleep 1 000 / 2 000 / 4 000 ms, `attempt++`, retry
- **L183–L185** — attempt 3 exhausted → `log.error`, `return false`

**`NotificationWorker.java` · `onMessage()`**

- **L125** — `success = false` → `ack(record)` is **not called** — record stays in the stream pending list

**`PendingSweeper.java` · `sweepStream()`** *(@Scheduled every 60 s)*

- **L59** — `opsForStream().pending(key, group, Range.unbounded(), 100)` — XPENDING: list unacknowledged records
- **L62** — catch → `log.warn`, return — Redis down, skip this sweep
- **L67** — if idle < 5 min → `continue` — skip; worker may still be processing
- **L72** — `opsForStream().claim(key, group, consumerName, CLAIM_MIN_IDLE, id)` — XCLAIM: take ownership of the idle record
- **L84** — if `deliveryCount >= 5` → `moveToDeadLetter(record, ...)`
- **L91** — else → `notificationWorker.onMessage(record)` — re-dispatch through the full pipeline; `DuplicateKeyException` on save = idempotent success

**`PendingSweeper.java` · `moveToDeadLetter()`**

- **L107–L108** — `FailedNotification.builder()...failedNotificationRepository.save(...)` — persist for ops inspection
- **L110–L112** — catch (Mongo down) → `log.error`, `return` without XACK — sweep retries next tick
- **L118** — `opsForStream().acknowledge(...)` — XACK **only after** successful Mongo save

---

### FAILURE 3 — Redis down → cache miss → MongoDB fallback

**`CacheService.java` · `getUnreadCount()`**

- **L51** — `redisTemplate.opsForValue().get(...)` — try Redis first
- **L52–L54** — catch → `log.warn`, `return null` — null = cache miss, never a 500

**`NotificationService.java` · `getUnreadCount()`**

- **L147** — `cacheService.getUnreadCount(userId)` returns `null` (Redis down)
- **L152** — `userNotificationCountRepository.findById(userId)` — fallback: read directly from MongoDB
- **L157** — `cacheService.saveUnreadCount(userId, count)` — attempt to warm the cache for next request
- **L159** — catch → `log.warn` — cache warm failed too; count is still returned correctly
- **L161** — `return count` — Redis outage is invisible to the API caller

Same pattern for notification page reads: `CacheService.getNotifications()` returns `null` on any Redis error → `NotificationService.getNotifications()` falls through to `queryWithFilters()` (MongoDB) at L74 and re-warms the cache at L107 after a successful read.

---

## KEY INVARIANTS

| Invariant | Where enforced |
|-----------|----------------|
| XACK only on full success | `onMessage()` L125 |
| `broadcastId` fixed before retry loop | `onMessage()` L121 |
| Redis failure never triggers MongoDB retry | `processForRecipient()` L216 (`try` wraps all Redis/SSE) |
| `DuplicateKeyException` = idempotent success | `processWithRetry()` L155–159 |
| Cache invalidated not written back | `processForRecipient()` L217 (no `saveUnreadCount` call) |
| Dead-letter XACK only after Mongo save | `moveToDeadLetter()` L118 |
| Redis down → zero errors returned to API | `CacheService` — every method returns `null` on exception |
| MongoDB down → 503 not 500 | `GlobalExceptionHandler.java` L105–111 |

---

## BLOCKHOUND RESULTS

**Test class:** `BlockHoundTest.java` — 185 tests total, 0 failures, 0 errors

**JVM flags added to `pom.xml`:**
- `-XX:+AllowRedefinitionToAddDeleteMethods` — required by BlockHound on JDK 13+ to redefine `Thread.sleep()` and `Unsafe.park()`
- `-XX:+EnableDynamicAgentLoading` — suppresses the Java 21 dynamic-agent warning

BlockHound is scoped to virtual threads named `blockhound-vt-*` only. All other virtual threads are unmonitored so the rest of the suite is unaffected.

---

### Test 1 — `threadSleep_onVirtualThread_isNonPinning` ✅ PASSED

**Verdict: `Thread.sleep()` at `processWithRetry()` L176 is safe. Do not replace it.**

50 virtual threads each sleeping 100 ms all completed in under 1 000 ms (concurrent, not serial). If `Thread.sleep()` pinned the carrier thread the total would have been close to 5 000 ms. On Java 21, sleep parks the virtual thread and unmounts it from the carrier so the carrier is immediately free to run other virtual threads.

---

### Test 2 — `noSynchronizedMethods_onKeyServiceClasses` ✅ PASSED

**Verdict: No `synchronized` methods found on any key service class.**

`synchronized` methods pin the carrier thread for their full duration on Java 21 virtual threads (JEP 444). All six classes passed the reflection check:

| Class                 | Result                  |
|-----------------------|-------------------------|
| `NotificationWorker`  | no synchronized methods |
| `CacheService`        | no synchronized methods |
| `SseService`          | no synchronized methods |
| `NotificationService` | no synchronized methods |
| `DlqWorker`           | no synchronized methods |
| `PendingSweeper`      | no synchronized methods |

The `AtomicBoolean` guard in `ArchiveScheduler` is the correct lightweight alternative already in use.

---

### Test 3 — `cacheService_noBlockingOnVirtualThread` ✅ PASSED

**Verdict: `invalidateUserCache()`, `saveUnreadCount()`, and `getUnreadCount()` make no unexpected blocking calls on a virtual thread.**

Ran with a mocked `RedisTemplate` (no real network I/O) on a `blockhound-vt-cache` thread. BlockHound did not fire.

---

### Test 4 — `redisKeyValidator_noBlockingOnVirtualThread` ✅ PASSED

**Verdict: `RedisKeyValidator.validate()` is pure computation — zero blocking.**

All four branches (valid input, null, empty, bad format) ran on a `blockhound-vt-validator` thread. BlockHound did not fire. The method is a null check, empty check, and a pre-compiled regex match — no I/O, no locking, no park.

---

### Test 5 — `notificationWorker_onMessage_happyPath_noBlockingOnVirtualThread` ✅ PASSED

**Verdict: The full `onMessage()` happy-path makes no unexpected blocking calls on a virtual thread.**

Ran the complete pipeline on a `blockhound-vt-worker` thread with all external dependencies mocked. A real `CacheService` instance was used (not a mock) so BlockHound checked the actual cache code paths. `Thread.sleep()` in `processWithRetry()` was never reached because the mocked `save()` succeeded on the first attempt. The `allowBlockingCallsInside` whitelist for `processWithRetry()` documents the deliberate choice that back-off sleep is safe on Java 21 virtual threads for the failure path.
