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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
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
public class NotificationWorker implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String DLQ_KEY     = "notifications_dlq";
    private static final int    MAX_RETRIES = 3;

    private final RedisTemplate<String, Object>  redisTemplate;
    private final NotificationRepository         notificationRepository;
    private final FailedNotificationRepository   failedNotificationRepository;
    private final CacheService                   cacheService;
    private final SseService                     sseService;
    private final NotificationService            notificationService;
    private final ObjectMapper                   objectMapper;
    private final Validator                      validator;
    private final MongoTemplate                  mongoTemplate;
    private final StringRedisTemplate            stringRedisTemplate;
    private final String                         consumerGroup;

    public NotificationWorker(
            RedisTemplate<String, Object> redisTemplate,
            NotificationRepository notificationRepository,
            FailedNotificationRepository failedNotificationRepository,
            CacheService cacheService,
            SseService sseService,
            NotificationService notificationService,
            ObjectMapper objectMapper,
            Validator validator,
            MongoTemplate mongoTemplate,
            StringRedisTemplate stringRedisTemplate,
            @Value("${redis.stream.consumer-group}") String consumerGroup) {
        this.redisTemplate                = redisTemplate;
        this.notificationRepository       = notificationRepository;
        this.failedNotificationRepository = failedNotificationRepository;
        this.cacheService                 = cacheService;
        this.sseService                   = sseService;
        this.notificationService          = notificationService;
        this.objectMapper                 = objectMapper;
        this.validator                    = validator;
        this.mongoTemplate                = mongoTemplate;
        this.stringRedisTemplate          = stringRedisTemplate;
        this.consumerGroup                = consumerGroup;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String streamKey = record.getStream();
        log.info("Processing message {} from stream {}", record.getId(), streamKey);

        String payloadJson = record.getValue().get("payload");
        if (payloadJson == null) {
            log.error("Record {} on stream {} missing 'payload' field — sending to DLQ.", record.getId(), streamKey);
            pushToDlq(record.getValue(), "Missing payload field");
            ack(record);
            return;
        }

        NotificationPayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, NotificationPayload.class);
        } catch (Exception e) {
            log.error("Cannot deserialize record {} on stream {} — sending to DLQ.", record.getId(), streamKey, e);
            pushToDlq(record.getValue(), e.getMessage());
            ack(record);
            return;
        }

        Set<ConstraintViolation<NotificationPayload>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining(", "));
            log.error("Invalid payload in record {} — {}. Sending to DLQ.", record.getId(), errors);
            pushToDlq(record.getValue(), errors);
            ack(record);
            return;
        }

        // Content quality: taskTitle is required for COMMENT notifications.
        if ("COMMENT".equals(payload.getType()) &&
                (payload.getTaskTitle() == null || payload.getTaskTitle().isBlank())) {
            log.error("COMMENT notification in record {} is missing taskTitle — sending to DLQ.", record.getId());
            pushToDlq(record.getValue(), "taskTitle is required for type=COMMENT");
            ack(record);
            return;
        }

        // Deduplicate channels preserving insertion order
        payload.setChannels(new ArrayList<>(new LinkedHashSet<>(payload.getChannels())));

        // broadcastId is fixed for this job so all recipients share the same ID across retries
        String broadcastId = UUID.randomUUID().toString();

        boolean success = processWithRetry(payload, broadcastId);
        if (success) {
            ack(record);
        }
        // On transient failure: no XACK — message stays pending for PendingSweeper to retry
    }

    private void ack(MapRecord<String, String, String> record) {
        try {
            stringRedisTemplate.opsForStream()
                    .acknowledge(record.getStream(), consumerGroup, record.getId());
        } catch (Exception e) {
            log.warn("XACK failed for record {} on stream {} — it may be re-delivered",
                    record.getId(), record.getStream(), e);
        }
    }

    // Returns true when all recipients succeeded; false when max retries are exhausted.
    // Caller must NOT XACK on false so PendingSweeper can retry via XCLAIM.
    private boolean processWithRetry(NotificationPayload payload, String broadcastId) {
        Set<String> succeeded = new HashSet<>();
        int         attempt   = 0;

        while (attempt <= MAX_RETRIES) {
            boolean anyFailed = false;

            for (String recipientId : payload.getRecipientIds()) {
                if (succeeded.contains(recipientId)) continue;

                try {
                    processForRecipient(payload, recipientId, broadcastId);
                    succeeded.add(recipientId);
                } catch (DuplicateKeyException e) {
                    // Already saved on a previous attempt — idempotent, treat as success
                    log.warn("Duplicate key for broadcastId {} recipient {} — already saved, skipping",
                            broadcastId, recipientId);
                    succeeded.add(recipientId);
                } catch (Exception e) {
                    anyFailed = true;
                    log.error("Failed broadcastId {} recipient {}. Attempt {}/{}",
                            broadcastId, recipientId, attempt, MAX_RETRIES, e);
                }
            }

            if (!anyFailed) {
                log.info("Successfully processed broadcastId {} for {} recipient(s)",
                        broadcastId, payload.getRecipientIds().size());
                return true;
            }

            if (attempt < MAX_RETRIES) {
                long delayMs = (long) Math.pow(2, attempt) * 1000; // 1 s → 2 s → 4 s
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                attempt++;
            } else {
                log.error("Max retries reached for broadcastId {}. Message will remain pending for PendingSweeper.",
                        broadcastId);
                return false;
            }
        }
        return false;
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
                .taskTitle(payload.getTaskTitle())
                .redirectUri(payload.getRedirectUri())
                .imageUrl(payload.getImageUrl())
                .metadata(payload.getMetadata())
                .state("UNSEEN")
                .createdAt(Instant.now())
                .build();

        // MongoDB save is the critical operation — exceptions propagate to the retry loop
        notification = notificationRepository.save(notification);

        List<String> channels = payload.getChannels();

        // Cache refresh is best-effort. A Redis failure must NOT reach the retry loop —
        // doing so would re-execute the MongoDB save above and create duplicate notifications.
        try {
            cacheService.invalidateUserCache(recipientId);

            // Atomic $inc on unread counter; invalidate cache rather than writing back —
            // a concurrent $inc could race our cache write and leave a stale value.
            mongoTemplate.findAndModify(
                    new Query(Criteria.where("_id").is(recipientId)),
                    new Update().inc("count", 1),
                    FindAndModifyOptions.options().returnNew(true).upsert(true),
                    UserNotificationCount.class);

            if (channels != null && channels.contains("IN_APP")) {
                sseService.pushNotification(
                        recipientId, notificationService.mapToResponse(notification));
                long unreadCount = notificationService.getUnreadCount(recipientId);
                sseService.pushUnreadCount(recipientId, unreadCount);
            }
        } catch (Exception e) {
            log.warn("Cache/SSE refresh failed for user {} after save — cache may be stale until TTL",
                    recipientId, e);
        }

        if (channels != null && channels.contains("EMAIL")) {
            log.info("EMAIL channel — to be implemented with SendGrid");
        }
        if (channels != null && channels.contains("SMS")) {
            log.info("SMS channel — to be implemented with Twilio");
        }
        if (channels != null && channels.contains("PUSH")) {
            log.info("PUSH channel — to be implemented with FCM");
        }
    }

    /**
     * Push an unprocessable job to the Redis DLQ.
     * Falls back to MongoDB when Redis is also unavailable.
     */
    private void pushToDlq(Object rawJob, String reason) {
        try {
            redisTemplate.opsForList().rightPush(DLQ_KEY, rawJob);
        } catch (Exception redisEx) {
            log.error("Redis DLQ push failed — falling back to MongoDB failed_notifications", redisEx);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawPayload = objectMapper.convertValue(rawJob, Map.class);
                FailedNotification failed = FailedNotification.builder()
                        .originalPayload(rawPayload)
                        .failureReason(reason)
                        .failedAt(Instant.now())
                        .build();
                failedNotificationRepository.save(failed);
                log.warn("Job saved to MongoDB failed_notifications as DLQ fallback. Reason: {}", reason);
            } catch (Exception mongoEx) {
                log.error("MongoDB DLQ fallback also failed — job permanently lost. Reason: {}. Raw: {}",
                        reason, rawJob, mongoEx);
            }
        }
    }
}
