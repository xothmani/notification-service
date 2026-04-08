package com.notifications.notificationservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

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
        redisTemplate.opsForValue().set(UNREAD_COUNT_KEY + userId, count, CACHE_TTL);
    }

    public Long getUnreadCount(String userId) {
        Object value = redisTemplate.opsForValue().get(UNREAD_COUNT_KEY + userId);
        if (value == null) return null;
        return ((Number) value).longValue();
    }

    // --- Notification pages ---

    /**
     * Build a deterministic cache key that encodes all query parameters.
     * Callers must use this method — never construct the key manually.
     */
    public String buildNotificationCacheKey(String userId, String organizationId,
                                            String state, String tier, String type,
                                            int page, int limit) {
        return String.format("%s%s:%s:%s:%s:%s:%d:%d",
                NOTIFICATIONS_KEY,
                userId,
                organizationId != null ? organizationId : "",
                state       != null ? state       : "",
                tier        != null ? tier        : "",
                type        != null ? type        : "",
                page, limit);
    }

    public void saveNotifications(String cacheKey,
                                  PageResponse<NotificationResponse> data) {
        redisTemplate.opsForValue().set(cacheKey, data, CACHE_TTL);
    }

    public PageResponse<NotificationResponse> getNotifications(String cacheKey) {
        Object raw = redisTemplate.opsForValue().get(cacheKey);
        if (raw == null) return null;
        return objectMapper.convertValue(
                raw, new TypeReference<PageResponse<NotificationResponse>>() {});
    }

    // --- Invalidation ---

    /**
     * Delete the unread count and ALL notification page caches for a user.
     * Uses Redis KEYS — acceptable for this service; replace with SCAN in high-scale prod.
     */
    public void invalidateUserCache(String userId) {
        redisTemplate.delete(UNREAD_COUNT_KEY + userId);

        Set<String> notificationKeys =
                redisTemplate.keys(NOTIFICATIONS_KEY + userId + ":*");
        if (notificationKeys != null && !notificationKeys.isEmpty()) {
            redisTemplate.delete(notificationKeys);
        }
    }
}