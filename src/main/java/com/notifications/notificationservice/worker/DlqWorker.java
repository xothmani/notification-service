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

    private static final String DLQ_KEY       = "notifications_dlq";
    // Process at most this many DLQ entries per sweep to avoid blocking the
    // scheduler thread for an unbounded time after a long outage.
    private static final int    MAX_PER_SWEEP = 100;

    private final RedisTemplate<String, Object> redisTemplate;
    private final FailedNotificationRepository failedNotificationRepository;
    private final ObjectMapper objectMapper;

    // Run every 30 seconds — sweep the DLQ
    @Scheduled(fixedDelay = 30000)
    public void sweepDlq() {
        log.info("Sweeping DLQ...");

        int processed = 0;

        while (processed < MAX_PER_SWEEP) {
            Object job;
            try {
                job = redisTemplate.opsForList().leftPop(DLQ_KEY);
            } catch (Exception e) {
                log.error("Redis unavailable during DLQ sweep — aborting sweep. Will retry next tick.", e);
                return;
            }

            if (job == null) break;

            log.warn("Found failed job in DLQ. Saving to MongoDB.");

            boolean saved = false;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> originalPayload = toMap(job);

                FailedNotification failedNotification =
                        FailedNotification.builder()
                                .originalPayload(originalPayload)
                                .failedChannel("UNKNOWN")
                                .failureReason("Max retries exceeded")
                                .failedAt(Instant.now())
                                .build();

                failedNotificationRepository.save(failedNotification);
                log.info("Failed notification saved to MongoDB");
                saved = true;

            } catch (Exception e) {
                log.error("Could not save DLQ job to MongoDB — re-queuing to DLQ to avoid data loss", e);
            }

            // If MongoDB save failed, push the job back to the DLQ tail so it is
            // not lost. The next sweep will retry it once MongoDB recovers.
            if (!saved) {
                try {
                    redisTemplate.opsForList().rightPush(DLQ_KEY, job);
                    log.warn("Re-queued unsaveable DLQ job back to DLQ tail");
                } catch (Exception reQueueEx) {
                    log.error("Could not re-queue DLQ job — job permanently lost. Raw: {}", job, reQueueEx);
                }
                // Stop processing this sweep; MongoDB is likely down
                return;
            }

            processed++;
        }

        if (processed > 0) {
            log.info("DLQ sweep complete — {} job(s) processed", processed);
        }
    }

    /**
     * Convert an arbitrary job object to Map.
     * If the object is already a Map (common case), returns it directly.
     * If it cannot be converted, returns a single-key map containing the raw string
     * representation so the record is never silently dropped.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object job) {
        try {
            return objectMapper.convertValue(job, Map.class);
        } catch (Exception e) {
            log.warn("DLQ job could not be converted to Map — storing raw string representation", e);
            return Map.of("raw", String.valueOf(job),
                          "conversionError", e.getMessage());
        }
    }
}
