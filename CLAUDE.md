# Notification Service — Claude Code Rules

## Project
Spring Boot 3.3 + Java 21 notification microservice.
MongoDB, Redis, SSE, Virtual Threads.
Based on Aziz's spec v2.1.
Serves Survey Teams and Team Boost.

## Tech Stack
- Backend: Spring Boot 3.3 + Java 21
- Database: MongoDB
- Cache: Redis (Cache-Aside pattern)
- Queue: Redis Queue
- Real-time: SSE (Server-Sent Events)
- Security: Internal token filter for /api/internal/**
- Concurrency: Virtual threads enabled

### Redis Validation (RedisKeyValidator)
- userId null → throw IllegalArgumentException("USER_ID_NULL") → 400
- userId empty → throw IllegalArgumentException("USER_ID_EMPTY") → 400
- userId wrong format → throw IllegalArgumentException("USER_ID_INVALID_FORMAT") → 422
- userId must be looked up in user service before cache operations
- RedisKeyValidator must be called in EVERY CacheService method
- Redis template must be checked for null before every operation

### Redis Health
- Every Redis operation wrapped in try-catch
- If Redis is down → log warning → return null → fallback to MongoDB
- Never return 500 because Redis is down
- Redis timeout: 2000ms connect, 2000ms operation

### Cache Policy
- Eviction policy: allkeys-lru
- Max memory: 256mb
- TTL: 5 minutes on every cache entry
- Cache warming on startup: load top 100 users unread counts

### Cache Spill Policy
- When Redis reaches max memory → allkeys-lru evicts least recently used
- Active users keep their cache
- Inactive users get evicted
- Never return OOM errors to users

### MongoDB Health
- Every MongoDB operation wrapped in try-catch
- If MongoDB is down → return 503 Service Unavailable
- MongoDB timeout configured in connection URI

### Error Codes
- 400 → null or empty input
- 422 → wrong format (USER_ID_INVALID_FORMAT)
- 401 → missing or wrong internal token
- 404 → resource not found
- 503 → MongoDB or Redis down
- 500 → unexpected error only
- 4xx → log.warn
- 5xx → log.error
- Missing request header → 400 not 500
- Wrong type in query param → 400 not 500

### Validation Rules
- Every String input: check null, empty, format
- X-User-Id header: @NotBlank @Pattern validation
- notificationId path variable: @NotBlank
- broadcastId path variable: @NotBlank
- page: minimum 1
- limit: minimum 1, maximum 100
- tier and type: must match ^[A-Z_]+$
- recipient_ids: not null, not empty, each item not blank
- channels: not null, not empty, must be IN_APP/EMAIL/SMS/PUSH
- redirectUri: must match ^https?://.*

### Worker Safety
- broadcastId generated BEFORE retry loop
- Redis failure after MongoDB save must NOT trigger retry
- DLQ push must have MongoDB fallback if Redis is down
- recipient_ids null check before processing
- channels null check before processing
- All required fields checked after deserialization

### Archive Job Safety
- Batch size: 500 max
- DuplicateKeyException on insert → treated as already archived
- MongoDB failure → abort batch, log error, resume next run
- AtomicBoolean prevents concurrent runs
- No max-run-time guard needed for now

### Security
- internal.token must come from INTERNAL_TOKEN env variable
- Default dev token must log a WARNING on startup
- Token comparison must be constant-time (MessageDigest.isEqual)

### Future — Not Built Yet (waiting for spec)
- Keycloak JWT integration — userId will come from JWT token
- EMAIL channel — SendGrid (waiting for spec)
- SMS channel — Twilio (waiting for spec)
- PUSH channel — FCM (waiting for spec)
- DM/Chat service — separate service, out of scope
- Bot ID field — waiting for Derek's spec
- URL field — waiting for Derek's spec
- User-to-user notifications — waiting for Derek's spec

## Code Quality Rules
- No dead code
- No unused imports
- No System.out.println — use log.info/warn/error
- No hardcoded secrets
- No business logic in controllers
- Controller → Service → Repository only
- Batch operations over N individual saves
- Cache invalidate on every write

## Testing Rules
- Every fix must have a test
- Test happy path
- Test null input
- Test empty input
- Test wrong format
- Test external system down
- Test boundary values
- 146 tests must always pass
- BUILD SUCCESS required before pushing

## Before Every Push
1. Run: ./mvnw test
2. Must show: BUILD SUCCESS
3. Must show: 0 failures
4. Then: git add . && git commit && git push
