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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWorker {

    private static final String QUEUE_KEY   = "notifications_queue";
    private static final String DLQ_KEY     = "notifications_dlq";
    private static final int    MAX_RETRIES = 3;

    private final RedisTemplate<String, Object>  redisTemplate;
    private final NotificationRepository         notificationRepository;
    private final FailedNotificationRepository   failedNotificationRepository;
    private final CacheService                   cacheService;
    private final SseService                     sseService;
    private final NotificationService            notificationService;
    private final ObjectMapper                   objectMapper;
    private final Validator                      validator;      // FIX 2: Bean Validation
    private final MongoTemplate                  mongoTemplate;  // FIX 6: atomic $inc

    // Poll queue every second
    @Scheduled(fixedDelay = 1000)
    public void processQueue() {
        Object job;
        try {
            job = redisTemplate.opsForList().leftPop(QUEUE_KEY);
        } catch (Exception e) {
            log.warn("Redis unavailable — could not poll queue. Will retry next tick.", e);
            return;
        }

        if (job == null) return;

        log.info("Processing notification job from queue");

        // Deserialize once before entering the retry loop.
        // A malformed payload cannot be fixed by retrying — send straight to DLQ.
        NotificationPayload payload;
        try {
            payload = objectMapper.convertValue(job, NotificationPayload.class);
        } catch (Exception e) {
            log.error("Malformed queue job — cannot deserialize payload. Sending to DLQ.", e);
            pushToDlq(job, e.getMessage(), null);
            return;
        }

        // FIX 2: Use Bean Validation instead of manual checks.
        // objectMapper.convertValue() does NOT trigger @NotBlank/@NotEmpty annotations,
        // so we run the validator programmatically here.
        Set<ConstraintViolation<NotificationPayload>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
            log.error("Invalid payload — {}. Sending to DLQ.", errors);
            pushToDlq(job, errors, null);
            return;
        }

        // FIX 7: Deduplicate channels preserving insertion order.
        // Prevents the same channel being processed twice if the producer sends duplicates.
        payload.setChannels(new ArrayList<>(new LinkedHashSet<>(payload.getChannels())));

        // broadcastId is fixed for this job. Generating it inside the retry loop
        // would assign different IDs to duplicated notifications on each retry attempt.
        String broadcastId = UUID.randomUUID().toString();

        processWithRetry(job, payload, broadcastId);
    }

    // FIX 1: Track per-recipient success so that retries only re-process failed recipients.
    // DuplicateKeyException from the unique {broadcast_id, recipient_id} index means the
    // notification was already saved (idempotent) — treat as success, do not retry.
    private void processWithRetry(Object rawJob,
                                  NotificationPayload payload,
                                  String broadcastId) {
        Set<String> succeeded = new HashSet<>();
        int attempt = 0;

        while (attempt <= MAX_RETRIES) {
            boolean anyFailed = false;
            Exception lastException = null;

            for (String recipientId : payload.getRecipientIds()) {
                if (succeeded.contains(recipientId)) continue;

                try {
                    processForRecipient(payload, recipientId, broadcastId);
                    succeeded.add(recipientId);
                } catch (DuplicateKeyException e) {
                    // Already saved on a previous retry — idempotent, treat as success
                    log.warn("Duplicate key for broadcastId {} recipient {} — already saved, skipping",
                            broadcastId, recipientId);
                    succeeded.add(recipientId);
                } catch (Exception e) {
                    anyFailed = true;
                    lastException = e;
                    log.error("Failed to process broadcastId {} for recipient {}. Attempt {}/{}",
                            broadcastId, recipientId, attempt, MAX_RETRIES, e);
                }
            }

            if (!anyFailed) {
                log.info("Successfully processed broadcastId {} for {} recipient(s)",
                        broadcastId, payload.getRecipientIds().size());
                return;
            }

            if (attempt < MAX_RETRIES) {
                // Exponential back-off: 1 s → 2 s → 4 s
                long delayMs = (long) Math.pow(2, attempt) * 1000;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempt++;
            } else {
                log.error("Max retries reached for broadcastId {}. Moving to DLQ", broadcastId);
                // FIX 3: pass the actual exception message and channels context
                String reason = lastException != null ? lastException.getMessage() : "Max retries exceeded";
                pushToDlq(rawJob, reason, String.join(",", payload.getChannels()));
                return;
            }
        }
    }

    private void processForRecipient(NotificationPayload payload,
                                     String recipientId,
                                     String broadcastId) {
        Notification notification = Notification.builder()
                .broadcastId(broadcastId)
                .recipientId(recipientId)
                .organizationId(payload.getOrganizationId())
                .tier(payload.getTier())
                .type(payload.getType())
                .title(payload.getTitle())
                .description(payload.getDescription())
                .redirectUri(payload.getRedirectUri())
                .imageUrl(payload.getImageUrl())
                .metadata(payload.getMetadata())
                .state("UNSEEN")
                .createdAt(Instant.now())
                .build();

        // MongoDB save is the critical operation — exceptions here propagate to the
        // retry loop so the job is retried or moved to DLQ.
        notification = notificationRepository.save(notification);

        // Cache refresh is best-effort. Wrapping in try-catch ensures a Redis
        // failure does NOT propagate to the retry loop — which would cause the
        // MongoDB save above (already persisted) to be executed again on retry,
        // creating duplicate notifications for the same recipient.
        try {
            cacheService.invalidateUserCache(recipientId);

            // FIX 6: Atomically increment the unread counter document via $inc.
            // This avoids a full collection scan on countByRecipientIdAndState.
            UserNotificationCount counter = mongoTemplate.findAndModify(
                    new Query(Criteria.where("_id").is(recipientId)),
                    new Update().inc("count", 1),
                    FindAndModifyOptions.options().returnNew(true).upsert(true),
                    UserNotificationCount.class);
            long unreadCount = counter != null ? counter.getCount() : 0L;
            cacheService.saveUnreadCount(recipientId, unreadCount);

            // IN_APP — push via SSE if user is connected
            List<String> channels = payload.getChannels();
            if (channels.contains("IN_APP")) {
                sseService.pushNotification(
                        recipientId, notificationService.mapToResponse(notification));
                sseService.pushUnreadCount(recipientId, unreadCount);
            }
        } catch (Exception e) {
            log.warn("Cache/SSE refresh failed for user {} after notification save — " +
                     "cache may be stale until TTL, SSE not pushed", recipientId, e);
        }

        // EMAIL — placeholder (implement with SendGrid)
        if (payload.getChannels().contains("EMAIL")) {
            log.info("EMAIL channel — to be implemented with SendGrid");
        }

        // SMS — placeholder (implement with Twilio)
        if (payload.getChannels().contains("SMS")) {
            log.info("SMS channel — to be implemented with Twilio");
        }

        // PUSH — placeholder (implement with FCM)
        if (payload.getChannels().contains("PUSH")) {
            log.info("PUSH channel — to be implemented with FCM");
        }
    }

    /**
     * Push a failed job to the Redis DLQ.
     * If Redis is also unavailable, falls back to persisting directly to MongoDB.
     *
     * @param channel null when the failure is not channel-specific (deserialization/validation);
     *                comma-separated channel list when max retries are exhausted.
     */
    private void pushToDlq(Object rawJob, String reason, String channel) {
        try {
            redisTemplate.opsForList().rightPush(DLQ_KEY, rawJob);
        } catch (Exception redisEx) {
            log.error("Redis DLQ push failed — falling back to MongoDB failed_notifications", redisEx);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawPayload = objectMapper.convertValue(rawJob, Map.class);
                FailedNotification failed = FailedNotification.builder()
                        .originalPayload(rawPayload)
                        .failedChannel(channel)
                        .failureReason(reason)
                        .failedAt(Instant.now())
                        .build();
                failedNotificationRepository.save(failed);
                log.warn("Job saved to MongoDB failed_notifications as DLQ fallback. Reason: {}", reason);
            } catch (Exception mongoEx) {
                log.error("MongoDB DLQ fallback also failed — job permanently lost. Reason: {}. Raw job: {}",
                        reason, rawJob, mongoEx);
            }
        }
    }

    // Called by channel implementations when a specific delivery channel fails
    void saveFailedChannel(NotificationPayload payload, String channel, String reason) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> originalPayload =
                    objectMapper.convertValue(payload, Map.class);

            FailedNotification failed = FailedNotification.builder()
                    .originalPayload(originalPayload)
                    .failedChannel(channel)
                    .failureReason(reason)
                    .failedAt(Instant.now())
                    .build();

            failedNotificationRepository.save(failed);
            log.warn("Saved failed channel {} to failed_notifications. Reason: {}",
                    channel, reason);
        } catch (Exception e) {
            log.error("Could not persist failed notification to MongoDB", e);
        }
    }
}
