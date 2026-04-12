package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationPayload;
import com.notifications.notificationservice.model.FailedNotification;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.model.UserNotificationCount;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.service.CacheService;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.service.SseService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationWorkerTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock NotificationRepository notificationRepository;
    @Mock FailedNotificationRepository failedNotificationRepository;
    @Mock CacheService cacheService;
    @Mock SseService sseService;
    @Mock NotificationService notificationService;
    @Mock ObjectMapper objectMapper;
    @Mock ListOperations<String, Object> listOps;
    @Mock MongoTemplate mongoTemplate;

    // Use the real validator so @NotBlank, @Size, @Pattern etc. are actually enforced
    private static final jakarta.validation.Validator REAL_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    // Worker is constructed manually so we can inject the real validator
    NotificationWorker worker;

    private static final String QUEUE_KEY = "notifications_queue";
    private static final String DLQ_KEY   = "notifications_dlq";

    private NotificationPayload validPayload;
    private Notification savedNotification;
    private Object rawJob;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        worker = new NotificationWorker(
                redisTemplate, notificationRepository, failedNotificationRepository,
                cacheService, sseService, notificationService, objectMapper,
                REAL_VALIDATOR, mongoTemplate);

        validPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1"))
                .organizationId("org1")
                .tier("HIGH")
                .type("ALERT")
                .title("Test Title")
                .description("Test Description")
                .redirectUri("https://example.com")
                .channels(List.of("IN_APP"))
                .build();

        savedNotification = Notification.builder()
                .id("n1")
                .recipientId("user1")
                .state("UNSEEN")
                .build();

        rawJob = new Object();
    }

    // ===================================================================
    // Empty queue
    // ===================================================================

    @Test
    void emptyQueue_doesNothing() {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(null);
        worker.processQueue();
        verifyNoInteractions(notificationRepository, objectMapper);
    }

    // ===================================================================
    // Redis queue down
    // ===================================================================

    @Test
    void redisPollFails_returnsGracefullyWithoutProcessing() {
        when(listOps.leftPop(QUEUE_KEY))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThatNoException().isThrownBy(() -> worker.processQueue());
        verifyNoInteractions(notificationRepository, objectMapper);
    }

    // ===================================================================
    // Malformed / invalid payloads → DLQ immediately, no MongoDB
    // ===================================================================

    @Nested
    class InvalidPayload {

        @Test
        void malformedJson_goesToDlqWithoutMongoSave() throws Exception {
            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class))
                    .thenThrow(new IllegalArgumentException("parse error"));

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void nullRecipientIds_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(null).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void emptyRecipientIds_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of()).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void nullChannels_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(null)
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void blankTitle_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("   ").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void nullTitle_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title(null).description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void nullTier_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier(null).type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
        }

        // FIX 8: @Size constraints
        @Test
        void oversizedTitle_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T".repeat(501)).description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void oversizedDescription_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D".repeat(2001))
                    .redirectUri("https://x.com").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void oversizedRedirectUri_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://" + "x".repeat(195) + ".com")
                    .organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }

        @Test
        void invalidRedirectUri_goesToDlqWithoutMongoSave() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("not-a-url").organizationId("org1").build();

            when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
            when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(bad);

            worker.processQueue();

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
        }
    }

    // ===================================================================
    // Happy path
    // ===================================================================

    @Test
    void relativeRedirectUri_passesValidationAndSavesToMongo() throws Exception {
        NotificationPayload relativeUri = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("/surveys/q3-eng").channels(List.of("IN_APP")).build();

        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(relativeUri);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any()))
                .thenReturn(new com.notifications.notificationservice.dto.NotificationResponse());

        worker.processQueue();

        verify(notificationRepository, times(1)).save(any());
        verify(listOps, never()).rightPush(eq(DLQ_KEY), any());
    }

    @Test
    void happyPath_savesToMongoAndRefreshesCache() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(validPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        worker.processQueue();

        verify(notificationRepository, times(1)).save(any());
        // Worker invalidates the cache — it does NOT write a new value back to Redis
        // because the $inc + Redis write is not atomic. The next read will miss and
        // re-populate from MongoDB.
        verify(cacheService).invalidateUserCache("user1");
        verify(cacheService, never()).saveUnreadCount(anyString(), anyLong());
    }

    @Test
    void happyPath_inAppChannel_pushesViaSse() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(validPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any()))
                .thenReturn(new com.notifications.notificationservice.dto.NotificationResponse());
        when(notificationService.getUnreadCount("user1")).thenReturn(3L);

        worker.processQueue();

        verify(sseService).pushNotification(eq("user1"), any());
        verify(sseService).pushUnreadCount("user1", 3L);
    }

    @Test
    void happyPath_multipleRecipients_savesForEach() throws Exception {
        NotificationPayload multiPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2", "user3"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP")).build();

        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(multiPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any()))
                .thenReturn(new com.notifications.notificationservice.dto.NotificationResponse());

        worker.processQueue();

        verify(notificationRepository, times(3)).save(any());
    }

    @Test
    void threeRecipients_allShareSameBroadcastId() throws Exception {
        // broadcastId is generated ONCE before the recipient loop — every recipient
        // in the same broadcast must receive the identical broadcastId.
        NotificationPayload multiPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2", "user3"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP")).build();

        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(multiPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any()))
                .thenReturn(new com.notifications.notificationservice.dto.NotificationResponse());

        worker.processQueue();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());

        List<String> broadcastIds = captor.getAllValues().stream()
                .map(Notification::getBroadcastId)
                .collect(Collectors.toList());

        assertThat(broadcastIds).hasSize(3);
        assertThat(broadcastIds.get(0)).isNotNull();
        assertThat(broadcastIds).containsOnly(broadcastIds.get(0)); // all three identical
    }

    // ===================================================================
    // FIX 7: Channel deduplication
    // ===================================================================

    @Test
    void duplicateChannels_processedOnlyOnce() throws Exception {
        NotificationPayload dupPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP", "IN_APP", "IN_APP")).build();

        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(dupPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any()))
                .thenReturn(new com.notifications.notificationservice.dto.NotificationResponse());

        worker.processQueue();

        // Even though IN_APP appeared 3 times, it should be processed only once
        verify(sseService, times(1)).pushNotification(eq("user1"), any());
    }

    // ===================================================================
    // FIX 1: Per-recipient retry — DuplicateKeyException treated as success
    // ===================================================================

    @Test
    void duplicateKeyException_treatedAsSuccess_noRetry() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(validPayload);
        // First save throws DuplicateKeyException (already persisted by previous attempt)
        when(notificationRepository.save(any())).thenThrow(new DuplicateKeyException("dup"));

        worker.processQueue();

        // DuplicateKeyException is idempotent — no DLQ push, no retry needed
        verify(listOps, never()).rightPush(eq(DLQ_KEY), any());
    }

    @Test
    void perRecipientRetry_successfulRecipientNotReprocessed() throws Exception {
        NotificationPayload twoRecipients = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP")).build();

        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(twoRecipients);

        Notification n1 = Notification.builder().id("n1").recipientId("user1").state("UNSEEN").build();
        Notification n2 = Notification.builder().id("n2").recipientId("user2").state("UNSEEN").build();

        // user1 succeeds, user2 always fails
        when(notificationRepository.save(any()))
                .thenAnswer(inv -> {
                    Notification n = inv.getArgument(0);
                    if ("user1".equals(n.getRecipientId())) return n1;
                    throw new RuntimeException("user2 Mongo fail");
                });

        worker.processQueue();

        // Capture all save invocations — user1 must appear exactly once (succeeded on first attempt,
        // never retried); user2 will appear multiple times (retried up to MAX_RETRIES).
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.atLeast(1)).save(captor.capture());

        long user1Saves = captor.getAllValues().stream()
                .filter(n -> "user1".equals(n.getRecipientId())).count();
        assertThat(user1Saves).isEqualTo(1L);
    }

    // ===================================================================
    // CRITICAL: Redis failure after MongoDB save must NOT duplicate save
    // ===================================================================

    @Test
    void redisFailureAfterMongoSave_saveCalledExactlyOnce() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(validPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        // Simulate Redis failure in the cache block (after save succeeds)
        doThrow(new RuntimeException("Redis connection lost"))
                .when(cacheService).invalidateUserCache(anyString());

        worker.processQueue();

        // The inner try-catch in processForRecipient swallows the Redis exception.
        // The retry loop does NOT see an exception, so save is called exactly ONCE.
        verify(notificationRepository, times(1)).save(any());
    }

    // ===================================================================
    // Max retries exhausted → DLQ called exactly once
    // ===================================================================

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void maxRetriesExhausted_pushToDlqCalledExactlyOnce() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(validPayload);
        // All MongoDB saves fail — exhausts all 3 retries
        when(notificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo unavailable"));

        worker.processQueue();

        // DLQ push happens exactly once after all retries are exhausted
        verify(listOps, times(1)).rightPush(eq(DLQ_KEY), any());
        // Save was attempted MAX_RETRIES+1 = 4 times (attempts 0, 1, 2, 3)
        verify(notificationRepository, times(4)).save(any());
    }

    // ===================================================================
    // DLQ push fallback when Redis DLQ is also down
    // ===================================================================

    @Test
    void dlqPushFails_fallsBackToMongoDB() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class))
                .thenThrow(new IllegalArgumentException("bad payload"));
        when(listOps.rightPush(eq(DLQ_KEY), any()))
                .thenThrow(new DataAccessResourceFailureException("Redis DLQ down"));
        when(objectMapper.convertValue(rawJob, java.util.Map.class)).thenReturn(java.util.Map.of());

        worker.processQueue();

        // Falls back to MongoDB failed_notifications
        verify(failedNotificationRepository).save(any(FailedNotification.class));
    }

    @Test
    void dlqPushFailsAndMongoFallbackFails_doesNotPropagate() throws Exception {
        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class))
                .thenThrow(new IllegalArgumentException("bad"));
        when(listOps.rightPush(eq(DLQ_KEY), any()))
                .thenThrow(new DataAccessResourceFailureException("DLQ Redis down"));
        when(objectMapper.convertValue(rawJob, java.util.Map.class)).thenReturn(java.util.Map.of());
        when(failedNotificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo also down"));

        // Both fallbacks fail — must not propagate; job is permanently lost (logged)
        assertThatNoException().isThrownBy(() -> worker.processQueue());
    }

    // ===================================================================
    // Non-IN_APP channels (placeholders, no crash)
    // ===================================================================

    @Test
    void emailChannel_doesNotThrow() throws Exception {
        NotificationPayload emailPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("EMAIL")).build();

        when(listOps.leftPop(QUEUE_KEY)).thenReturn(rawJob);
        when(objectMapper.convertValue(rawJob, NotificationPayload.class)).thenReturn(emailPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        assertThatNoException().isThrownBy(() -> worker.processQueue());
        verify(sseService, never()).pushNotification(any(), any());
    }
}
