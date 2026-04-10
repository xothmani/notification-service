package com.notifications.notificationservice.service;

import com.notifications.notificationservice.dto.BroadcastStatsResponse;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.exception.NotFoundException;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.model.UserNotificationCount;
import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.repository.UserNotificationCountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserNotificationCountRepository userNotificationCountRepository;
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

        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }

        String cacheKey = cacheService.buildNotificationCacheKey(
                userId, organizationId, state, tier, type, page, limit);

        // 1. Check cache first
        PageResponse<NotificationResponse> cached =
                cacheService.getNotifications(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for key {}", cacheKey);
            return cached;
        }

        // 2. Cache miss — query MongoDB with all active filters combined.
        //    Secondary sort by _id provides a stable tie-breaker when multiple
        //    documents share the same created_at timestamp (virtual threads / batch inserts).
        Pageable pageable = PageRequest.of(
                page - 1, limit,
                Sort.by(
                        Sort.Order.desc("created_at"),
                        Sort.Order.asc("_id")));

        Page<Notification> dbPage = queryWithFilters(
                userId, organizationId, state, tier, type, pageable);

        // 3. FIX 10: If the live collection returns an empty page for page > 1,
        //    fall back to the archive collection so paginated reads span both stores.
        if (dbPage.getContent().isEmpty() && page > 1) {
            Page<Notification> archivePage = queryArchiveWithFilters(
                    userId, organizationId, state, tier, type, pageable);

            if (archivePage.hasContent()) {
                long unreadCount = getUnreadCount(userId);
                sseService.pushUnreadCount(userId, unreadCount);

                PageResponse<NotificationResponse> archiveResponse =
                        PageResponse.from(archivePage.map(this::mapToResponse));
                archiveResponse.setSource("archive");

                cacheService.saveNotifications(cacheKey, archiveResponse);
                return archiveResponse;
            }
        }

        // 4. Mark any UNSEEN notifications on this page as SEEN
        markUnseenAsSeen(userId, dbPage.getContent());

        // 5. Get fresh unread count and push via SSE
        long unreadCount = getUnreadCount(userId);
        sseService.pushUnreadCount(userId, unreadCount);

        // 6. Build response and cache it
        PageResponse<NotificationResponse> response =
                PageResponse.from(dbPage.map(this::mapToResponse));

        cacheService.saveNotifications(cacheKey, response);

        return response;
    }

    // Mark notification as clicked — requires ownership check (FIX 5)
    public NotificationResponse markAsClicked(String notificationId, String userId) {
        Notification notification = notificationRepository
                .findById(notificationId)
                .orElseThrow(() -> new NotFoundException(
                        "Notification not found: " + notificationId));

        // Ownership check — do not reveal existence of other users' notifications
        if (!userId.equals(notification.getRecipientId())) {
            throw new NotFoundException("Notification not found: " + notificationId);
        }

        // Idempotent — skip DB write if already CLICKED
        if ("CLICKED".equals(notification.getState())) {
            return mapToResponse(notification);
        }

        notification.setState("CLICKED");
        notification.setClickedAt(Instant.now());
        notification = notificationRepository.save(notification);

        // Cache invalidation is best-effort — failure here must not fail the request
        try {
            cacheService.invalidateUserCache(notification.getRecipientId());
        } catch (Exception e) {
            log.warn("Cache invalidation failed after markAsClicked for user {} — cache may be stale until TTL",
                    notification.getRecipientId(), e);
        }

        return mapToResponse(notification);
    }

    // Get unread count for a user (Cache-Aside).
    // Reads from UserNotificationCount document maintained by the worker via $inc.
    public long getUnreadCount(String userId) {
        Long cached = cacheService.getUnreadCount(userId);
        if (cached != null) {
            return cached;
        }

        UserNotificationCount counter =
                userNotificationCountRepository.findById(userId).orElse(null);
        long count = counter != null ? counter.getCount() : 0L;

        try {
            cacheService.saveUnreadCount(userId, count);
        } catch (Exception e) {
            log.warn("Cache write failed for unread count for user {} — count still returned", userId, e);
        }
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

    // Build a dynamic MongoDB query combining all provided filters via andOperator.
    private Page<Notification> queryWithFilters(
            String userId, String organizationId, String state,
            String tier, String type, Pageable pageable) {

        List<Criteria> filters = buildFilters(userId, organizationId, state, tier, type);
        Criteria combined = new Criteria().andOperator(filters);

        long total = mongoTemplate.count(new Query(combined), Notification.class);
        List<Notification> content = mongoTemplate.find(
                new Query(combined).with(pageable), Notification.class);

        return new PageImpl<>(content, pageable, total);
    }

    // FIX 10: Query the archive collection as a fallback when live collection is empty on page > 1.
    private Page<Notification> queryArchiveWithFilters(
            String userId, String organizationId, String state,
            String tier, String type, Pageable pageable) {

        List<Criteria> filters = buildFilters(userId, organizationId, state, tier, type);
        Criteria combined = new Criteria().andOperator(filters);

        long total = mongoTemplate.count(
                new Query(combined), Notification.class, "notifications_archive");
        List<Notification> content = mongoTemplate.find(
                new Query(combined).with(pageable), Notification.class, "notifications_archive");

        return new PageImpl<>(content, pageable, total);
    }

    private List<Criteria> buildFilters(String userId, String organizationId,
                                        String state, String tier, String type) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("recipient_id").is(userId));
        if (organizationId != null) filters.add(Criteria.where("organization_id").is(organizationId));
        if (state         != null) filters.add(Criteria.where("state").is(state));
        if (tier          != null) filters.add(Criteria.where("tier").is(tier));
        if (type          != null) filters.add(Criteria.where("type").is(type));
        return filters;
    }

    // Mark all UNSEEN notifications in the given list as SEEN.
    // Also resets the UserNotificationCount for the user.
    // Cache invalidation after the DB write is best-effort.
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

        // Reset unread counter to 0 — best-effort, failure must not fail the request
        try {
            mongoTemplate.findAndModify(
                    new Query(Criteria.where("_id").is(userId)),
                    new Update().set("count", 0),
                    FindAndModifyOptions.options().returnNew(true).upsert(true),
                    UserNotificationCount.class);
        } catch (Exception e) {
            log.warn("Failed to reset unread counter for user {} after SEEN update — counter may drift until next worker update", userId, e);
        }

        try {
            cacheService.invalidateUserCache(userId);
        } catch (Exception e) {
            log.warn("Cache invalidation failed after SEEN update for user {} — cache may be stale until TTL", userId, e);
        }

        log.info("Marked {} notifications as SEEN for user {}", unseen.size(), userId);
    }
}
