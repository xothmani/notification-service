package com.notifications.notificationservice.worker;

import com.notifications.notificationservice.model.FailedNotification;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PendingSweeper {

    private static final int      MAX_PENDING_PER_STREAM = 100;
    private static final int      MAX_DELIVERY_COUNT     = 5;
    private static final Duration CLAIM_MIN_IDLE         = Duration.ofMinutes(5);

    @Value("${redis.stream.key}")
    private String streamKey;

    @Value("${redis.stream.consumer-group}")
    private String consumerGroup;

    @Value("${redis.stream.consumer-name}")
    private String consumerName;

    private final StringRedisTemplate          stringRedisTemplate;
    private final NotificationWorker           notificationWorker;
    private final FailedNotificationRepository failedNotificationRepository;

    public PendingSweeper(
            StringRedisTemplate stringRedisTemplate,
            NotificationWorker notificationWorker,
            FailedNotificationRepository failedNotificationRepository) {
        this.stringRedisTemplate          = stringRedisTemplate;
        this.notificationWorker           = notificationWorker;
        this.failedNotificationRepository = failedNotificationRepository;
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        sweepStream(streamKey);
    }

    private void sweepStream(String key) {
        PendingMessages pending;
        try {
            pending = stringRedisTemplate.opsForStream()
                    .pending(key, consumerGroup, Range.unbounded(), MAX_PENDING_PER_STREAM);
        } catch (Exception e) {
            log.warn("Could not read pending messages from stream {}: {}", key, e.getMessage());
            return;
        }

        for (PendingMessage pm : pending) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(CLAIM_MIN_IDLE) < 0) continue;

            List<MapRecord<String, String, String>> claimed;
            try {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, String, String>> c =
                        (List<MapRecord<String, String, String>>) (List<?>)
                                stringRedisTemplate.opsForStream()
                                        .claim(key, consumerGroup, consumerName, CLAIM_MIN_IDLE, pm.getId());
                claimed = c;
            } catch (Exception e) {
                log.warn("Could not claim record {} from stream {}: {}",
                        pm.getId(), key, e.getMessage());
                continue;
            }

            for (MapRecord<String, String, String> record : claimed) {
                if (pm.getTotalDeliveryCount() >= MAX_DELIVERY_COUNT) {
                    moveToDeadLetter(record,
                            "Exceeded max delivery count: " + pm.getTotalDeliveryCount());
                } else {
                    log.info("Re-dispatching record {} (delivery #{}) from stream {}",
                            record.getId(), pm.getTotalDeliveryCount(), key);
                    try {
                        notificationWorker.onMessage(record);
                    } catch (Exception e) {
                        log.error("Re-dispatch failed for record {} from stream {}",
                                record.getId(), key, e);
                    }
                }
            }
        }
    }

    private void moveToDeadLetter(MapRecord<String, String, String> record, String reason) {
        log.error("Moving record {} from stream {} to dead letter. Reason: {}",
                record.getId(), record.getStream(), reason);
        try {
            Map<String, Object> payload = new HashMap<>(record.getValue());
            FailedNotification failed = FailedNotification.builder()
                    .originalPayload(payload)
                    .failureReason(reason)
                    .failedAt(Instant.now())
                    .build();
            failedNotificationRepository.save(failed);
        } catch (Exception e) {
            log.error("Could not save dead-letter record {} to MongoDB — record not acknowledged",
                    record.getId(), e);
            return; // Don't XACK if we couldn't persist — sweep will retry
        }

        // XACK only after successful MongoDB save to avoid silent data loss
        try {
            stringRedisTemplate.opsForStream()
                    .acknowledge(record.getStream(), consumerGroup, record.getId());
        } catch (Exception e) {
            log.warn("XACK failed for dead-letter record {} — it will be re-swept", record.getId(), e);
        }
    }
}
