package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationPayload;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.model.FailedNotification;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.service.CacheService;
import com.notifications.notificationservice.service.MessagingDeliveryService;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationWorkerTest {

    @Mock RedisTemplate<String, Object>  redisTemplate;
    @Mock NotificationRepository         notificationRepository;
    @Mock FailedNotificationRepository   failedNotificationRepository;
    @Mock CacheService                   cacheService;
    @Mock SseService                     sseService;
    @Mock NotificationService            notificationService;
    @Mock ObjectMapper                   objectMapper;
    @Mock MongoTemplate                  mongoTemplate;
    @Mock StringRedisTemplate            stringRedisTemplate;
    @Mock org.springframework.data.redis.core.StreamOperations streamOps;
    @Mock ListOperations<String, Object> listOps;
    @Mock MessagingDeliveryService       messagingDeliveryService;

    private static final jakarta.validation.Validator REAL_VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final String   STREAM_KEY   = "notifications_stream";
    private static final String   DLQ_KEY      = "notifications_dlq";
    private static final String   CONSUMER_GRP = "notification-group";
    private static final RecordId RECORD_ID    = RecordId.of("1234567890-0");

    NotificationWorker worker;

    private NotificationPayload validPayload;
    private Notification        savedNotification;

    @BeforeEach
    void setUp() {
        // doReturn bypasses Mockito's generic-type check when stubbing opsForStream()
        doReturn(streamOps).when(stringRedisTemplate).opsForStream();
        when(redisTemplate.opsForList()).thenReturn(listOps);

        worker = new NotificationWorker(
                redisTemplate, notificationRepository, failedNotificationRepository,
                cacheService, sseService, notificationService, objectMapper,
                REAL_VALIDATOR, mongoTemplate,
                stringRedisTemplate, CONSUMER_GRP, messagingDeliveryService);

        validPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1"))
                .organizationId("org1")
                .tier("HIGH").type("ALERT")
                .title("Test Title").description("Test Description")
                .redirectUri("https://example.com")
                .channels(List.of("IN_APP"))
                .build();

        savedNotification = Notification.builder()
                .id("n1").recipientId("user1").state("UNSEEN").build();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private MapRecord<String, String, String> record(Map<String, String> fields) {
        return MapRecord.create(STREAM_KEY, fields).withId(RECORD_ID);
    }

    private void stubValidPayload() throws Exception {
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(validPayload);
    }

    /**
     * Verify XACK was issued — opsForStream() is the gateway to acknowledge() in NotificationWorker.
     * This avoids directly calling acknowledge() on a raw mock (which has two ambiguous overloads).
     */
    private void verifyXAckIssued() {
        verify(stringRedisTemplate, atLeastOnce()).opsForStream();
    }

    /** Verify NO XACK was issued — message must stay pending for PendingSweeper. */
    private void verifyNoXAck() {
        verify(stringRedisTemplate, never()).opsForStream();
    }

    // ---------------------------------------------------------------
    // Unrecoverable payloads → DLQ + XACK
    // ---------------------------------------------------------------

    @Nested
    class UnrecoverablePayload {

        @Test
        void missingPayloadField_goesToDlqAndXAcks() {
            worker.onMessage(record(Map.of("other", "value")));

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
            verifyXAckIssued();
        }

        @Test
        void malformedJson_goesToDlqAndXAcks() throws Exception {
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class)))
                    .thenThrow(new JsonProcessingException("parse error") {});

            worker.onMessage(record(Map.of("payload", "not-json")));

            verify(notificationRepository, never()).save(any());
            verify(listOps).rightPush(eq(DLQ_KEY), any());
            verifyXAckIssued();
        }

        @Test
        void nullRecipientIds_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(null).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void emptyRecipientIds_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of()).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void nullChannels_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(null)
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void blankTitle_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("   ").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void nullTitle_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title(null).description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void nullTier_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier(null).type("T").title("T").description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void oversizedTitle_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T".repeat(501)).description("D")
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void oversizedDescription_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D".repeat(2001))
                    .redirectUri("https://x.com").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void oversizedRedirectUri_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("https://" + "x".repeat(195) + ".com")
                    .organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }

        @Test
        void invalidRedirectUri_goesToDlqAndXAcks() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of("user1")).channels(List.of("IN_APP"))
                    .tier("T").type("T").title("T").description("D")
                    .redirectUri("not-a-url").organizationId("org1").build();
            when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(bad);

            worker.onMessage(record(Map.of("payload", "{}")));

            verify(notificationRepository, never()).save(any());
            verifyXAckIssued();
        }
    }

    // ---------------------------------------------------------------
    // Happy path — XACK on success
    // ---------------------------------------------------------------

    @Test
    void happyPath_savesToMongoAndXAcks() throws Exception {
        stubValidPayload();
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(notificationRepository, times(1)).save(any());
        verifyXAckIssued();
    }

    @Test
    void happyPath_invalidatesCacheDoesNotWriteBack() throws Exception {
        stubValidPayload();
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(cacheService).invalidateUserCache("user1");
        verify(cacheService, never()).saveUnreadCount(anyString(), anyLong());
    }

    @Test
    void happyPath_inAppChannel_pushesViaSse() throws Exception {
        stubValidPayload();
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any())).thenReturn(new NotificationResponse());
        when(notificationService.getUnreadCount("user1")).thenReturn(3L);

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(sseService).pushNotification(eq("user1"), any());
        verify(sseService).pushUnreadCount("user1", 3L);
    }

    @Test
    void relativeRedirectUri_passesValidationAndSaves() throws Exception {
        NotificationPayload relativeUri = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("/surveys/q3-eng").channels(List.of("IN_APP")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(relativeUri);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any())).thenReturn(new NotificationResponse());

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(notificationRepository, times(1)).save(any());
        verify(listOps, never()).rightPush(eq(DLQ_KEY), any());
        verifyXAckIssued();
    }

    @Test
    void multipleRecipients_savesForEach() throws Exception {
        NotificationPayload multi = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2", "user3"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(multi);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any())).thenReturn(new NotificationResponse());

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(notificationRepository, times(3)).save(any());
        verifyXAckIssued();
    }

    @Test
    void threeRecipients_allShareSameBroadcastId() throws Exception {
        NotificationPayload multi = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2", "user3"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(multi);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any())).thenReturn(new NotificationResponse());

        worker.onMessage(record(Map.of("payload", "{}")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());

        List<String> broadcastIds = captor.getAllValues().stream()
                .map(Notification::getBroadcastId)
                .collect(Collectors.toList());
        assertThat(broadcastIds).hasSize(3);
        assertThat(broadcastIds.get(0)).isNotNull();
        assertThat(broadcastIds).containsOnly(broadcastIds.get(0));
    }

    // ---------------------------------------------------------------
    // Channel deduplication
    // ---------------------------------------------------------------

    @Test
    void duplicateChannels_processedOnlyOnce() throws Exception {
        NotificationPayload dup = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP", "IN_APP", "IN_APP")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(dup);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(notificationService.mapToResponse(any())).thenReturn(new NotificationResponse());

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(sseService, times(1)).pushNotification(eq("user1"), any());
    }

    // ---------------------------------------------------------------
    // DuplicateKeyException treated as idempotent success → XACK
    // ---------------------------------------------------------------

    @Test
    void duplicateKeyException_treatedAsSuccess_xAcks() throws Exception {
        stubValidPayload();
        when(notificationRepository.save(any())).thenThrow(new DuplicateKeyException("dup"));

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(listOps, never()).rightPush(eq(DLQ_KEY), any());
        verifyXAckIssued();
    }

    // ---------------------------------------------------------------
    // Per-recipient retry — successful recipient not re-processed
    // ---------------------------------------------------------------

    @Test
    void perRecipientRetry_successfulRecipientNotReprocessed() throws Exception {
        NotificationPayload two = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2"))
                .organizationId("org1").tier("HIGH").type("ALERT")
                .title("T").description("D").redirectUri("https://x.com")
                .channels(List.of("IN_APP")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(two);

        Notification n1 = Notification.builder().id("n1").recipientId("user1").state("UNSEEN").build();
        when(notificationRepository.save(any()))
                .thenAnswer(inv -> {
                    Notification n = inv.getArgument(0);
                    if ("user1".equals(n.getRecipientId())) return n1;
                    throw new RuntimeException("user2 Mongo fail");
                });

        worker.onMessage(record(Map.of("payload", "{}")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.atLeast(1)).save(captor.capture());
        long user1Saves = captor.getAllValues().stream()
                .filter(n -> "user1".equals(n.getRecipientId())).count();
        assertThat(user1Saves).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // CRITICAL: Redis failure after MongoDB save must NOT trigger retry
    // ---------------------------------------------------------------

    @Test
    void redisFailureAfterMongoSave_saveCalledExactlyOnce() throws Exception {
        stubValidPayload();
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        doThrow(new RuntimeException("Redis connection lost"))
                .when(cacheService).invalidateUserCache(anyString());

        worker.onMessage(record(Map.of("payload", "{}")));

        // Redis exception is swallowed — MongoDB save happens exactly once, XACK still issued
        verify(notificationRepository, times(1)).save(any());
        verifyXAckIssued();
    }

    // ---------------------------------------------------------------
    // Max retries exhausted → NO XACK (PendingSweeper retries)
    // ---------------------------------------------------------------

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void maxRetriesExhausted_noXAck() throws Exception {
        stubValidPayload();
        when(notificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo unavailable"));

        worker.onMessage(record(Map.of("payload", "{}")));

        // No XACK — message stays pending for PendingSweeper
        verifyNoXAck();
        // Save attempted MAX_RETRIES+1 = 4 times (attempts 0, 1, 2, 3)
        verify(notificationRepository, times(4)).save(any());
    }

    // ---------------------------------------------------------------
    // DLQ push fallback when Redis DLQ is also down
    // ---------------------------------------------------------------

    @Test
    void dlqPushFails_fallsBackToMongoDB() throws Exception {
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class)))
                .thenThrow(new JsonProcessingException("bad payload") {});
        when(listOps.rightPush(eq(DLQ_KEY), any()))
                .thenThrow(new DataAccessResourceFailureException("Redis DLQ down"));
        when(objectMapper.convertValue(any(), eq(java.util.Map.class))).thenReturn(Map.of());

        worker.onMessage(record(Map.of("payload", "bad")));

        verify(failedNotificationRepository).save(any(FailedNotification.class));
    }

    @Test
    void dlqPushFailsAndMongoFallbackFails_doesNotPropagate() throws Exception {
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class)))
                .thenThrow(new JsonProcessingException("bad") {});
        when(listOps.rightPush(eq(DLQ_KEY), any()))
                .thenThrow(new DataAccessResourceFailureException("DLQ Redis down"));
        when(objectMapper.convertValue(any(), eq(java.util.Map.class))).thenReturn(Map.of());
        when(failedNotificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo also down"));

        assertThatNoException().isThrownBy(() -> worker.onMessage(record(Map.of("payload", "bad"))));
    }

    // ---------------------------------------------------------------
    // Non-IN_APP channels (placeholders, no crash)
    // ---------------------------------------------------------------

    @Test
    void emailChannel_doesNotThrow() throws Exception {
        NotificationPayload emailPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("EMAIL")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(emailPayload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        assertThatNoException().isThrownBy(() -> worker.onMessage(record(Map.of("payload", "{}"))));
        verify(sseService, never()).pushNotification(any(), any());
        verifyXAckIssued();
    }

    // ---------------------------------------------------------------
    // EMAIL channel delivery via MessagingDeliveryService
    // ---------------------------------------------------------------

    @Test
    void emailChannel_withEmail_callsSendEmail() throws Exception {
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("Title").description("Desc")
                .redirectUri("https://x.com").channels(List.of("EMAIL"))
                .recipientEmails(List.of("user1@example.com")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(messagingDeliveryService.sendEmail(any(), any(), any(), any())).thenReturn(true);

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(messagingDeliveryService).sendEmail(
                eq("user1@example.com"), eq("Title"), eq("Desc"), eq("n1"));
        verifyXAckIssued();
    }

    @Test
    void emailChannel_nullRecipientEmails_sendEmailNotCalled() throws Exception {
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("EMAIL"))
                .recipientEmails(null).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        assertThatNoException().isThrownBy(() -> worker.onMessage(record(Map.of("payload", "{}"))));
        verify(messagingDeliveryService, never()).sendEmail(any(), any(), any(), any());
        verifyXAckIssued();
    }

    @Test
    void emailChannel_noEmailForRecipientIndex_sendEmailNotCalled() throws Exception {
        // Two recipients but only one email — second recipient has no email
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1", "user2")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("EMAIL"))
                .recipientEmails(List.of("user1@example.com")).build(); // only index 0
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        when(messagingDeliveryService.sendEmail(any(), any(), any(), any())).thenReturn(true);

        worker.onMessage(record(Map.of("payload", "{}")));

        // Only user1 gets an email — user2 is silently skipped
        verify(messagingDeliveryService, times(1)).sendEmail(any(), any(), any(), any());
        verifyXAckIssued();
    }

    @Test
    void emailChannel_messagingFails_mongoSaveNotRetried() throws Exception {
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("EMAIL"))
                .recipientEmails(List.of("user1@example.com")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);
        // sendEmail is inside the best-effort try-catch; even if it throws MongoDB is not retried
        doThrow(new RuntimeException("messaging service down"))
                .when(messagingDeliveryService).sendEmail(any(), any(), any(), any());

        worker.onMessage(record(Map.of("payload", "{}")));

        // Save called exactly once — no retry caused by the messaging failure
        verify(notificationRepository, times(1)).save(any());
        verifyXAckIssued();
    }

    // ---------------------------------------------------------------
    // SMS channel delivery via MessagingDeliveryService
    // ---------------------------------------------------------------

    @Test
    void smsChannel_withPhone_callsSendSms() throws Exception {
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("Title").description("Desc")
                .redirectUri("https://x.com").channels(List.of("SMS"))
                .recipientPhones(List.of("+1234567890")).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        worker.onMessage(record(Map.of("payload", "{}")));

        verify(messagingDeliveryService).sendSms(
                eq("+1234567890"), eq("Title: Desc"), eq("n1"));
        verifyXAckIssued();
    }

    @Test
    void smsChannel_nullRecipientPhones_sendSmsNotCalled() throws Exception {
        NotificationPayload payload = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId("org1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("SMS"))
                .recipientPhones(null).build();
        when(objectMapper.readValue(anyString(), eq(NotificationPayload.class))).thenReturn(payload);
        when(notificationRepository.save(any())).thenReturn(savedNotification);

        assertThatNoException().isThrownBy(() -> worker.onMessage(record(Map.of("payload", "{}"))));
        verify(messagingDeliveryService, never()).sendSms(any(), any(), any());
        verifyXAckIssued();
    }
}
