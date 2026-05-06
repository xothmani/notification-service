package com.notifications.notificationservice.config;

import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pre-warms the Redis cache on startup with the unread counts for the
 * top 100 most recently active users. This prevents a thundering-herd
 * effect against MongoDB when the service restarts under load.
 *
 * If Redis or MongoDB is unavailable at startup the warmup is skipped
 * gracefully — the cache is lazily populated on first request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmupConfig implements ApplicationRunner {

    private static final int WARMUP_LIMIT = 100;

    private final NotificationRepository notificationRepository;
    private final MongoTemplate mongoTemplate;
    private final CacheService cacheService;


    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting cache warmup for top {} most recently active users...", WARMUP_LIMIT);

        try {
            warmupUnreadCounts();
        } catch (Exception e) {
            // Warmup failure must never prevent the application from starting.
            log.warn("Cache warmup failed — service will start with a cold cache. " +
                     "Cache will be populated lazily on first request.", e);
        }
    }

    /**
     * Aggregates UNSEEN notification counts per user, ordered by most recent
     * notification, and seeds each user's unread count into Redis.
     */
    private void warmupUnreadCounts() {
        // Aggregate: group by recipient_id where state=UNSEEN, sort by latest
        // created_at descending, limit to top WARMUP_LIMIT users.
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(
                        org.springframework.data.mongodb.core.query.Criteria
                                .where("state").is("UNSEEN")),
                Aggregation.group("recipient_id")
                        .count().as("unreadCount")
                        .max("created_at").as("latestAt"),
                Aggregation.sort(Sort.by(Sort.Direction.DESC, "latestAt")),
                Aggregation.limit(WARMUP_LIMIT)
        );

        AggregationResults<Map> results = mongoTemplate.aggregate(
                aggregation, "notifications", Map.class);

        List<Map> userCounts = results.getMappedResults();

        int warmedUp = 0;
        for (Map<?, ?> row : userCounts) {
            String userId = String.valueOf(row.get("_id"));
            Object countObj = row.get("unreadCount");
            if (userId == null || userId.equals("null") || countObj == null) continue;

            long count = ((Number) countObj).longValue();
            try {
                cacheService.saveUnreadCount(userId, count);
                warmedUp++;
            } catch (Exception e) {
                // Individual user failure must not abort the entire warmup
                log.warn("Could not warm cache for user {} — skipping", userId, e);
            }
        }

        log.info("Cache warmup complete — seeded unread counts for {} user(s)", warmedUp);
    }
}
