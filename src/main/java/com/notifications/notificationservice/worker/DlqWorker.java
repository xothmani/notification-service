package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.model.FailedNotification;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqWorker {

    private static final String DLQ_KEY = "notifications_dlq";

    private final RedisTemplate<String, Object> redisTemplate;
    private final FailedNotificationRepository failedNotificationRepository;
    private final ObjectMapper objectMapper;

    // Run every 30 seconds — sweep the DLQ
    @Scheduled(fixedDelay = 30000)
    public void sweepDlq() {
        log.info("Sweeping DLQ...");

        // Process all jobs in DLQ
        while (true) {
            // Pop one job from DLQ
            Object job = redisTemplate.opsForList().leftPop(DLQ_KEY);

            // If DLQ is empty — stop
            if (job == null) break;

            log.warn("Found failed job in DLQ. Saving to MongoDB.");

            try {
                // Convert to Map to get original payload
                @SuppressWarnings("unchecked")
                Map<String, Object> originalPayload =
                        (Map<String, Object>) objectMapper.convertValue(job, Map.class);

                // Save to failed_notifications collection
                FailedNotification failedNotification =
                        FailedNotification.builder()
                                .originalPayload(originalPayload)
                                .failedChannel("UNKNOWN")
                                .failureReason("Max retries exceeded")
                                .failedAt(Instant.now())
                                .build();

                failedNotificationRepository.save(failedNotification);

                log.info("Failed notification saved to MongoDB");

            } catch (Exception e) {
                log.error("Could not save failed job to MongoDB", e);
            }
        }
    }
}
