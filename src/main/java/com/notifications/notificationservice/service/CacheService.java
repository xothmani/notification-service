package com.notifications.notificationservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.util.RedisKeyValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final String UNREAD_COUNT_KEY  = "unread_count:";
    private static final String NOTIFICATIONS_KEY = "notifications:";

    // --- Unread count ---

    public void saveUnreadCount(String userId, long count) {
        RedisKeyValidator.validate(userId);
        if (redisTemplate == null) {
            log.error("redisTemplate is null — skipping saveUnreadCount for user {}", userId);
            return;
        }
        try {
            redisTemplate.opsForValue().set(UNREAD_COUNT_KEY + userId, count, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis error in saveUnreadCount for user {} — skipping cache write", userId, e);
        }
    }

    public Long getUnreadCount(String userId) {
        RedisKeyValidator.validate(userId);
        if (redisTemplate == null) {
            log.error("redisTemplate is null — returning cache miss for user {}", userId);
            return null;
        }
        try {
            Object value = redisTemplate.opsForValue().get(UNREAD_COUNT_KEY + userId);
            if (value == null) return null;
            return ((Number) value).longValue();
        } catch (Exception e) {
            log.warn("Redis error in getUnreadCount for user {} — returning cache miss", userId, e);
            return null;
        }
    }

    // --- Notification pages ---

    /**
     * Build a deterministic cache key that encodes all query parameters.
     * Callers must use this method — never construct the key manually.
     */
    public String buildNotificationCacheKey(String userId, String organizationId,
                                            String state, String tier, String type,
                                            int page, int limit) {
        RedisKeyValidator.validate(userId);
        String orgPart = organizationId != null ? ":" + organizationId : "";
        return String.format("%s%s%s:%s:%s:%s:%d:%d",
                NOTIFICATIONS_KEY,
                userId,
                orgPart,
                state != null ? state : "",
                tier  != null ? tier  : "",
                type  != null ? type  : "",
                page, limit);
    }

    public void saveNotifications(String cacheKey,
                                  PageResponse<NotificationResponse> data) {
        if (redisTemplate == null) {
            log.error("redisTemplate is null — skipping saveNotifications for key {}", cacheKey);
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, data, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis error in saveNotifications for key {} — skipping cache write", cacheKey, e);
        }
    }

    public PageResponse<NotificationResponse> getNotifications(String cacheKey) {
        if (redisTemplate == null) {
            log.error("redisTemplate is null — returning cache miss for key {}", cacheKey);
            return null;
        }
        try {
            Object raw = redisTemplate.opsForValue().get(cacheKey);
            if (raw == null) return null;
            return objectMapper.convertValue(
                    raw, new TypeReference<PageResponse<NotificationResponse>>() {});
        } catch (Exception e) {
            log.warn("Redis error in getNotifications for key {} — returning cache miss", cacheKey, e);
            return null;
        }
    }

    // --- Invalidation ---

    /**
     * Delete the unread count and ALL notification page caches for a user.
     * Uses Redis SCAN (non-blocking, cursor-based) — safe for production.
     * Best-effort: Redis failures are logged but never propagated to the caller.
     */
    public void invalidateUserCache(String userId) {
        RedisKeyValidator.validate(userId);
        if (redisTemplate == null) {
            log.error("redisTemplate is null — skipping invalidateUserCache for user {}", userId);
            return;
        }
        try {
            redisTemplate.delete(UNREAD_COUNT_KEY + userId);
        } catch (Exception e) {
            log.warn("Redis error deleting unread count for user {} — cache may be stale until TTL", userId, e);
        }

        try {
            String pattern = NOTIFICATIONS_KEY + userId + ":*";
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();

            List<String> keysToDelete = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keysToDelete.add(cursor.next());
                }
            }

            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
            }
        } catch (Exception e) {
            log.warn("Redis error scanning/deleting notification keys for user {} — cache may be stale until TTL", userId, e);
        }
    }
}
