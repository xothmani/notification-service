package com.notifications.notificationservice.config;

import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.service.CacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheWarmupConfigTest {

    @Mock NotificationRepository notificationRepository;
    @Mock MongoTemplate mongoTemplate;
    @Mock CacheService cacheService;

    @InjectMocks CacheWarmupConfig cacheWarmupConfig;

    private final ApplicationArguments args = mock(ApplicationArguments.class);

    // ---------------------------------------------------------------- happy path

    @Test
    void run_happyPath_seedsUnreadCountsForAllUsers() throws Exception {
        @SuppressWarnings("unchecked")
        AggregationResults<Map> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of(
                Map.of("_id", "user1", "unreadCount", 5, "latestAt", "2024-01-01"),
                Map.of("_id", "user2", "unreadCount", 3, "latestAt", "2024-01-01")
        ));
        doReturn(results).when(mongoTemplate).aggregate(any(Aggregation.class), eq("notifications"), any(Class.class));

        cacheWarmupConfig.run(args);

        verify(cacheService, times(1)).saveUnreadCount("user1", 5L);
        verify(cacheService, times(1)).saveUnreadCount("user2", 3L);
    }

    @Test
    void run_noActiveUsers_noRedisWrites() throws Exception {
        @SuppressWarnings("unchecked")
        AggregationResults<Map> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of());
        doReturn(results).when(mongoTemplate).aggregate(any(), eq("notifications"), any());

        cacheWarmupConfig.run(args);

        verify(cacheService, never()).saveUnreadCount(anyString(), anyLong());
    }

    // ---------------------------------------------------------------- MongoDB down

    @Test
    void run_mongoDown_doesNotPropagateAndServiceStarts() throws Exception {
        doThrow(new DataAccessException("Mongo down") {})
                .when(mongoTemplate).aggregate(any(), anyString(), any());

        // Warmup failure must never prevent the application from starting
        assertThatNoException().isThrownBy(() -> cacheWarmupConfig.run(args));
    }

    // ---------------------------------------------------------------- Redis down

    @Test
    void run_redisDown_doesNotPropagateAndServiceStarts() throws Exception {
        @SuppressWarnings("unchecked")
        AggregationResults<Map> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(
                List.of(Map.of("_id", "user1", "unreadCount", 5, "latestAt", "x")));
        doReturn(results).when(mongoTemplate).aggregate(any(), anyString(), any());
        doThrow(new RuntimeException("Redis down"))
                .when(cacheService).saveUnreadCount(anyString(), anyLong());

        assertThatNoException().isThrownBy(() -> cacheWarmupConfig.run(args));
    }

    // ---------------------------------------------------------------- partial failure

    @Test
    void run_partialRedisFailure_continuesForRemainingUsers() throws Exception {
        @SuppressWarnings("unchecked")
        AggregationResults<Map> results = mock(AggregationResults.class);
        when(results.getMappedResults()).thenReturn(List.of(
                Map.of("_id", "user1", "unreadCount", 5, "latestAt", "x"),
                Map.of("_id", "user2", "unreadCount", 3, "latestAt", "x")
        ));
        doReturn(results).when(mongoTemplate).aggregate(any(Aggregation.class), anyString(), any(Class.class));
        doThrow(new RuntimeException("Redis down for user1"))
                .when(cacheService).saveUnreadCount(eq("user1"), anyLong());

        // Even though user1 fails, user2 should still be seeded
        cacheWarmupConfig.run(args);
        verify(cacheService).saveUnreadCount("user2", 3L);
    }

    // ---------------------------------------------------------------- null / invalid row

    @Test
    void run_rowWithNullUserId_skipsRow() throws Exception {
        @SuppressWarnings("unchecked")
        AggregationResults<Map> results = mock(AggregationResults.class);
        // Row with "null" string _id (from String.valueOf(null))
        when(results.getMappedResults()).thenReturn(
                List.of(Map.of("_id", "null", "unreadCount", 5, "latestAt", "x")));
        doReturn(results).when(mongoTemplate).aggregate(any(), anyString(), any());

        cacheWarmupConfig.run(args);

        verify(cacheService, never()).saveUnreadCount(anyString(), anyLong());
    }
}
