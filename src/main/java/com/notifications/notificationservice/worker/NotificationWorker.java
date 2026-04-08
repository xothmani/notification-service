package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationPayload;
import com.notifications.notificationservice.model.FailedNotification;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.service.CacheService;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWorker {

    private static final String QUEUE_KEY  = "notifications_queue";
    private static final String DLQ_KEY    = "notifications_dlq";
    private static final int    MAX_RETRIES = 3;

    private final RedisTemplate<String, Object>  redisTemplate;
    private final NotificationRepository         notificationRepository;
    private final FailedNotificationRepository   failedNotificationRepository;
    private final CacheService                   cacheService;
    private final SseService                     sseService;
    private final NotificationService            notificationService;
    private final ObjectMapper                   objectMapper;

    // Poll queue every second
    @Scheduled(fixedDelay = 1000)
    public void processQueue() {
        Object job = redisTemplate.opsForList().leftPop(QUEUE_KEY);
        if (job == null) return;

        log.info("Processing notification job from queue");
        processWithRetry(job);
    }

    private void processWithRetry(Object job) {
        int attempt = 0;

        while (attempt <= MAX_RETRIES) {
            try {
                NotificationPayload payload =
                        objectMapper.convertValue(job, NotificationPayload.class);

                String broadcastId = UUID.randomUUID().toString();

                for (String recipientId : payload.getRecipientIds()) {
                    processForRecipient(payload, recipientId, broadcastId);
                }

                return; // success — exit retry loop

            } catch (Exception e) {
                log.error("Failed to process job. Attempt {}/{}", attempt, MAX_RETRIES, e);

                if (attempt < MAX_RETRIES) {
                    // Exponential back-off: 1s → 2s → 4s
                    // Virtual threads make sleeping here cheap
                    long delayMs = (long) Math.pow(2, attempt) * 1000;
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    attempt++;
                } else {
                    log.error("Max retries reached. Moving job to DLQ");
                    redisTemplate.opsForList().rightPush(DLQ_KEY, job);
                    return;
                }
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

        notification = notificationRepository.save(notification);

        // Invalidate user cache and refresh unread count
        cacheService.invalidateUserCache(recipientId);
        long unreadCount = notificationRepository
                .countByRecipientIdAndState(recipientId, "UNSEEN");
        cacheService.saveUnreadCount(recipientId, unreadCount);

        // IN_APP — push via SSE
        if (payload.getChannels().contains("IN_APP")) {
            sseService.pushNotification(
                    recipientId, notificationService.mapToResponse(notification));
            sseService.pushUnreadCount(recipientId, unreadCount);
        }

        // EMAIL — placeholder (implement with SendGrid)
        if (payload.getChannels().contains("EMAIL")) {
            try {
                log.info("EMAIL channel — to be implemented with SendGrid");
                // TODO: implement SendGrid
            } catch (Exception e) {
                saveFailedChannel(payload, "EMAIL", e.getMessage());
            }
        }

        // SMS — placeholder (implement with Twilio)
        if (payload.getChannels().contains("SMS")) {
            try {
                log.info("SMS channel — to be implemented with Twilio");
                // TODO: implement Twilio
            } catch (Exception e) {
                saveFailedChannel(payload, "SMS", e.getMessage());
            }
        }

        // PUSH — placeholder (implement with FCM)
        if (payload.getChannels().contains("PUSH")) {
            try {
                log.info("PUSH channel — to be implemented with FCM");
                // TODO: implement FCM
            } catch (Exception e) {
                saveFailedChannel(payload, "PUSH", e.getMessage());
            }
        }
    }

    private void saveFailedChannel(NotificationPayload payload,
                                   String channel,
                                   String reason) {
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
            log.error("Could not save failed notification to MongoDB", e);
        }
    }
}