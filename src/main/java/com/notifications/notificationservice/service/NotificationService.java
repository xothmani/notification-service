package com.notifications.notificationservice.service;

import com.notifications.notificationservice.dto.BroadcastStatsResponse;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.exception.NotFoundException;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final MongoTemplate mongoTemplate;
    private final CacheService cacheService;
    private final SseService sseService;

    // Get paginated notifications for a user (Cache-Aside)
    public PageResponse<NotificationResponse> getNotifications(
            String userId,
            String organizationId,
            String state,
            String tier,
            String type,
            int page,
            int limit) {

        String cacheKey = cacheService.buildNotificationCacheKey(
                userId, organizationId, state, tier, type, page, limit);

        // 1. Check cache first
        PageResponse<NotificationResponse> cached =
                cacheService.getNotifications(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for key {}", cacheKey);
            return cached;
        }

        // 2. Cache miss — query MongoDB with all active filters combined
        Pageable pageable = PageRequest.of(
                page - 1, limit,
                Sort.by(Sort.Direction.DESC, "created_at"));

        Page<Notification> dbPage = queryWithFilters(
                userId, organizationId, state, tier, type, pageable);

        // 3. Mark any UNSEEN notifications on this page as SEEN
        markUnseenAsSeen(userId, dbPage.getContent());

        // 4. Get fresh unread count and push via SSE
        long unreadCount = getUnreadCount(userId);
        sseService.pushUnreadCount(userId, unreadCount);

        // 5. Build response and cache it
        PageResponse<NotificationResponse> response =
                PageResponse.from(dbPage.map(this::mapToResponse));

        cacheService.saveNotifications(cacheKey, response);

        return response;
    }

    // Mark notification as clicked
    public NotificationResponse markAsClicked(String notificationId) {
        Notification notification = notificationRepository
                .findById(notificationId)
                .orElseThrow(() -> new NotFoundException(
                        "Notification not found: " + notificationId));

        notification.setState("CLICKED");
        notification.setClickedAt(Instant.now());
        notification = notificationRepository.save(notification);

        cacheService.invalidateUserCache(notification.getRecipientId());

        return mapToResponse(notification);
    }

    // Get unread count for a user (Cache-Aside)
    public long getUnreadCount(String userId) {
        Long cached = cacheService.getUnreadCount(userId);
        if (cached != null) {
            return cached;
        }

        long count = notificationRepository
                .countByRecipientIdAndState(userId, "UNSEEN");

        cacheService.saveUnreadCount(userId, count);
        return count;
    }

    // Get broadcast stats (no cache — internal/admin use only)
    public BroadcastStatsResponse getBroadcastStats(String broadcastId) {
        List<Notification> notifications =
                notificationRepository.findByBroadcastId(broadcastId);

        long totalSent    = notifications.size();
        long totalSeen    = notifications.stream()
                .filter(n -> "SEEN".equals(n.getState()) || "CLICKED".equals(n.getState()))
                .count();
        long totalClicked = notifications.stream()
                .filter(n -> "CLICKED".equals(n.getState()))
                .count();

        List<BroadcastStatsResponse.RecipientStat> recipients = notifications.stream()
                .map(n -> BroadcastStatsResponse.RecipientStat.builder()
                        .userId(n.getRecipientId())
                        .state(n.getState())
                        .seenAt(n.getSeenAt())
                        .clickedAt(n.getClickedAt())
                        .build())
                .toList();

        return BroadcastStatsResponse.builder()
                .broadcastId(broadcastId)
                .totalSent(totalSent)
                .totalSeen(totalSeen)
                .totalClicked(totalClicked)
                .recipients(recipients)
                .build();
    }

    // Map Notification entity to NotificationResponse DTO
    public NotificationResponse mapToResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .broadcastId(notification.getBroadcastId())
                .recipientId(notification.getRecipientId())
                .organizationId(notification.getOrganizationId())
                .tier(notification.getTier())
                .type(notification.getType())
                .title(notification.getTitle())
                .description(notification.getDescription())
                .redirectUri(notification.getRedirectUri())
                .imageUrl(notification.getImageUrl())
                .metadata(notification.getMetadata())
                .state(notification.getState())
                .createdAt(notification.getCreatedAt())
                .seenAt(notification.getSeenAt())
                .clickedAt(notification.getClickedAt())
                .build();
    }

    // Build a dynamic MongoDB query combining all provided filters
    private Page<Notification> queryWithFilters(
            String userId, String organizationId, String state,
            String tier, String type, Pageable pageable) {

        // Use MongoDB field names (matching @Field annotations on the model)
        Criteria criteria = Criteria.where("recipient_id").is(userId);
        if (organizationId != null) criteria.and("organization_id").is(organizationId);
        if (state         != null) criteria.and("state").is(state);
        if (tier          != null) criteria.and("tier").is(tier);
        if (type          != null) criteria.and("type").is(type);

        long total = mongoTemplate.count(new Query(criteria), Notification.class);
        List<Notification> content = mongoTemplate.find(
                new Query(criteria).with(pageable), Notification.class);

        return new PageImpl<>(content, pageable, total);
    }

    // Mark all UNSEEN notifications in the given list as SEEN
    private void markUnseenAsSeen(String userId, List<Notification> notifications) {
        List<Notification> unseen = notifications.stream()
                .filter(n -> "UNSEEN".equals(n.getState()))
                .toList();

        if (unseen.isEmpty()) return;

        unseen.forEach(n -> {
            n.setState("SEEN");
            n.setSeenAt(Instant.now());
        });

        notificationRepository.saveAll(unseen);
        cacheService.invalidateUserCache(userId);

        log.info("Marked {} notifications as SEEN for user {}", unseen.size(), userId);
    }
}