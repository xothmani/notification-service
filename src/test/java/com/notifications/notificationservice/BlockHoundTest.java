package com.notifications.notificationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationPayload;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.service.CacheService;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.service.SseService;
import com.notifications.notificationservice.util.RedisKeyValidator;
import com.notifications.notificationservice.worker.DlqWorker;
import com.notifications.notificationservice.worker.NotificationWorker;
import com.notifications.notificationservice.worker.PendingSweeper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.blockhound.BlockHound;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlockHoundTest {

    // ── field mocks (populated per test by Mockito) ───────────────────────────

    @Mock RedisTemplate<String, Object>   redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock Cursor<String>                  redisCursor;

    @Mock NotificationRepository          notificationRepository;
    @Mock FailedNotificationRepository    failedNotificationRepository;
    @Mock NotificationService             notificationServiceMock;
    @Mock SseService                      sseServiceMock;
    @Mock ObjectMapper                    objectMapper;
    @Mock MongoTemplate                   mongoTemplate;
    @Mock StringRedisTemplate             stringRedisTemplate;
    @Mock StreamOperations                streamOps;
    @Mock ListOperations<String, Object>  listOps;

    private static final Validator REAL_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    // ── BlockHound installation (once per JVM) ────────────────────────────────

    @BeforeAll
    static void installBlockHound() {
        BlockHound.builder()
                // Only monitor virtual threads that this test class creates explicitly.
                // Threads named "blockhound-vt-*" are treated as non-blocking so BlockHound
                // will flag any unexpected park/sleep/wait call made on them.
                // Other virtual threads (Spring request handling, other test classes) are
                // not monitored and are unaffected.
                .addDynamicThreadPredicate(t ->
                        t.isVirtual() && t.getName().startsWith("blockhound-vt-"))
                // Thread.sleep() in processWithRetry is intentional and safe on Java 21
                // virtual threads: the JVM unmounts the virtual thread from its carrier
                // so the carrier is NOT pinned and remains available for other virtual
                // threads during the back-off delay. BlockHound would otherwise flag it
                // because we marked "blockhound-vt-*" threads as non-blocking. This
                // allowance documents that deliberate, safe choice.
                .allowBlockingCallsInside(
                        "com.notifications.notificationservice.worker.NotificationWorker",
                        "processWithRetry")
                .install();
    }

    // ── 1. Thread.sleep() is non-pinning on Java 21 virtual threads ───────────

    @Test
    void threadSleep_onVirtualThread_isNonPinning() throws Exception {
        // If Thread.sleep() pinned the carrier thread, N concurrent virtual threads
        // sleeping 100 ms would run serially (total ≈ N × 100 ms).  On Java 21, sleep()
        // parks the virtual thread and unmounts it from the carrier so the carrier is
        // free to run other virtual threads — all N complete in ~100 ms concurrently.
        //
        // These threads are intentionally NOT named "blockhound-vt-*": we want them
        // to sleep freely to prove the non-pinning behaviour.  BlockHound is not
        // involved here; we are testing the JVM behaviour directly.
        int count = 50;
        CountDownLatch latch = new CountDownLatch(count);
        long startNs = System.nanoTime();

        for (int i = 0; i < count; i++) {
            Thread.ofVirtual().name("sleep-vt-" + i).start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            });
        }

        boolean allDone = latch.await(3, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertThat(allDone)
                .as("All 50 virtual threads must complete within 3 s")
                .isTrue();
        // 50 threads each sleeping 100 ms concurrently should finish well under 1 s.
        // A serial run would take ~5 000 ms, exposing carrier-thread pinning.
        assertThat(elapsedMs)
                .as("50 concurrent 100 ms sleeps should finish in under 1 000 ms "
                        + "(proves non-pinning). Actual: %d ms", elapsedMs)
                .isLessThan(1_000L);
    }

    // ── 2. No synchronized methods on key service classes ─────────────────────

    @Test
    void noSynchronizedMethods_onKeyServiceClasses() {
        // synchronized instance methods pin the carrier thread for their entire
        // duration on Java 21 virtual threads (JEP 444). None of our classes
        // should declare synchronized methods.
        //
        // Note: static synchronized methods (class-level lock) are equally dangerous
        // and are also checked here.  AtomicBoolean (used in ArchiveScheduler) is the
        // correct alternative to synchronized for lightweight boolean guards.
        List<Class<?>> toCheck = List.of(
                NotificationWorker.class,
                CacheService.class,
                SseService.class,
                NotificationService.class,
                DlqWorker.class,
                PendingSweeper.class
        );

        toCheck.forEach(clazz ->
                Arrays.stream(clazz.getDeclaredMethods()).forEach(method -> {
                    assertThat(Modifier.isSynchronized(method.getModifiers()))
                            .as("%s.%s() is declared synchronized — this pins the carrier "
                                    + "thread when invoked on a Java 21 virtual thread",
                                    clazz.getSimpleName(), method.getName())
                            .isFalse();
                }));
    }

    // ── 3. CacheService — no unexpected blocking on a virtual thread ──────────

    @Test
    void cacheService_noBlockingOnVirtualThread() throws Exception {
        // CacheService is backed by a mocked RedisTemplate so no real network I/O
        // takes place.  BlockHound monitors this thread and will throw
        // BlockingOperationError if any unexpected park/sleep/wait is detected.
        doReturn(valueOps).when(redisTemplate).opsForValue();
        doReturn(redisCursor).when(redisTemplate).scan(any());
        when(redisCursor.hasNext()).thenReturn(false); // empty key scan

        CacheService cacheService = new CacheService(redisTemplate, new ObjectMapper());

        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread vt = Thread.ofVirtual()
                .name("blockhound-vt-cache")
                .uncaughtExceptionHandler((t, e) -> uncaught.set(e))
                .start(() -> {
                    cacheService.invalidateUserCache("user-123");
                    cacheService.saveUnreadCount("user-123", 5L);
                    cacheService.getUnreadCount("user-123");
                });
        vt.join(5_000);

        assertThat(uncaught.get())
                .as("CacheService must not make unexpected blocking calls on a virtual thread. "
                        + "BlockingOperationError: %s", uncaught.get())
                .isNull();
    }

    // ── 4. RedisKeyValidator — pure-logic path, no blocking ───────────────────

    @Test
    void redisKeyValidator_noBlockingOnVirtualThread() throws Exception {
        // RedisKeyValidator.validate() is seven lines of pure Java: null check,
        // empty check, pre-compiled regex match.  Zero I/O, zero locking.
        // BlockHound should never fire on this code path.
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread vt = Thread.ofVirtual()
                .name("blockhound-vt-validator")
                .uncaughtExceptionHandler((t, e) -> uncaught.set(e))
                .start(() -> {
                    RedisKeyValidator.validate("user-123");
                    try { RedisKeyValidator.validate(null);           } catch (IllegalArgumentException ignored) {}
                    try { RedisKeyValidator.validate("");             } catch (IllegalArgumentException ignored) {}
                    try { RedisKeyValidator.validate("bad user id");  } catch (IllegalArgumentException ignored) {}
                });
        vt.join(5_000);

        assertThat(uncaught.get())
                .as("RedisKeyValidator.validate() must not make any blocking calls "
                        + "on a virtual thread")
                .isNull();
    }

    // ── 5. NotificationWorker.onMessage() — happy path, no unexpected blocking ─

    @Test
    void notificationWorker_onMessage_happyPath_noBlockingOnVirtualThread() throws Exception {
        // Run the full happy-path of onMessage() on a BlockHound-monitored virtual
        // thread.  All external dependencies are mocked so no real I/O occurs.
        // The real CacheService is used (not a mock) so BlockHound checks its code.
        // processWithRetry() returns true on the first attempt — Thread.sleep() in
        // the back-off branch is never reached on the happy path.
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1"))
                .organizationId("org1")
                .tier("HIGH").type("ALERT")
                .title("Demo title").description("Demo description")
                .redirectUri("https://example.com")
                .channels(List.of("IN_APP"))
                .build();

        Notification saved = Notification.builder()
                .id("n1").recipientId("user1").state("UNSEEN").build();

        // Stub stream ops for XACK
        doReturn(streamOps).when(stringRedisTemplate).opsForStream();
        when(redisTemplate.opsForList()).thenReturn(listOps);

        // Stub deserialization, save, and SSE helpers
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(saved);
        when(notificationServiceMock.mapToResponse(any())).thenReturn(new NotificationResponse());
        when(notificationServiceMock.getUnreadCount("user1")).thenReturn(0L);

        // Real CacheService backed by its own local mocks so BlockHound checks
        // the actual CacheService code paths, not a stub
        RedisTemplate<String, Object> cacheRedis = mock(RedisTemplate.class);
        ValueOperations<String, Object> cacheValueOps = mock(ValueOperations.class);
        Cursor<String> cacheCursor = mock(Cursor.class);
        doReturn(cacheValueOps).when(cacheRedis).opsForValue();
        doReturn(cacheCursor).when(cacheRedis).scan(any());
        when(cacheCursor.hasNext()).thenReturn(false);
        CacheService realCacheService = new CacheService(cacheRedis, new ObjectMapper());

        NotificationWorker worker = new NotificationWorker(
                redisTemplate, notificationRepository, failedNotificationRepository,
                realCacheService, sseServiceMock, notificationServiceMock,
                objectMapper, REAL_VALIDATOR, mongoTemplate,
                stringRedisTemplate, "test-group");

        MapRecord<String, String, String> record =
                MapRecord.create("notifications_stream", Map.of("payload", "{}"))
                         .withId(RecordId.of("1-0"));

        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread vt = Thread.ofVirtual()
                .name("blockhound-vt-worker")
                .uncaughtExceptionHandler((t, e) -> uncaught.set(e))
                .start(() -> worker.onMessage(record));
        vt.join(10_000);

        assertThat(uncaught.get())
                .as("NotificationWorker.onMessage() must not make unexpected blocking calls "
                        + "on a virtual thread. Error: %s", uncaught.get())
                .isNull();
    }
}
