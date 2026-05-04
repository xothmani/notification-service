package com.notifications.notificationservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ObjectMapper objectMapper;
    @Mock ValueOperations<String, Object> valueOps;

    @InjectMocks CacheService cacheService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ===================================================================
    // saveUnreadCount
    // ===================================================================

    @Nested
    class SaveUnreadCount {

        @Test
        void nullUserId_throwsUserIdNull() {
            assertThatThrownBy(() -> cacheService.saveUnreadCount(null, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("USER_ID_NULL");
            verify(valueOps, never()).set(any(), any(), any());
        }

        @Test
        void emptyUserId_throwsUserIdEmpty() {
            assertThatThrownBy(() -> cacheService.saveUnreadCount("", 5))
                    .hasMessage("USER_ID_EMPTY");
            verify(valueOps, never()).set(any(), any(), any());
        }

        @Test
        void invalidFormat_throwsUserIdInvalidFormat() {
            assertThatThrownBy(() -> cacheService.saveUnreadCount("user:id", 5))
                    .hasMessage("USER_ID_INVALID_FORMAT");
            verify(valueOps, never()).set(any(), any(), any());
        }

        @Test
        void happyPath_callsRedisSetWithCorrectKey() {
            assertThatNoException().isThrownBy(() -> cacheService.saveUnreadCount("user1", 10));
            verify(valueOps).set(eq("unread_count:user1"), eq(10L), any(java.time.Duration.class));
        }

        @Test
        void redisFails_doesNotPropagate() {
            doThrow(new DataAccessResourceFailureException("Redis down"))
                    .when(valueOps).set(any(), any(), any());
            assertThatNoException().isThrownBy(() -> cacheService.saveUnreadCount("user1", 10));
        }
    }

    // ===================================================================
    // getUnreadCount
    // ===================================================================

    @Nested
    class GetUnreadCount {

        @Test
        void nullUserId_throwsUserIdNull() {
            assertThatThrownBy(() -> cacheService.getUnreadCount(null))
                    .hasMessage("USER_ID_NULL");
        }

        @Test
        void emptyUserId_throwsUserIdEmpty() {
            assertThatThrownBy(() -> cacheService.getUnreadCount(""))
                    .hasMessage("USER_ID_EMPTY");
        }

        @Test
        void invalidFormat_throwsUserIdInvalidFormat() {
            assertThatThrownBy(() -> cacheService.getUnreadCount("user:id"))
                    .hasMessage("USER_ID_INVALID_FORMAT");
        }

        @Test
        void cacheMiss_returnsNull() {
            when(valueOps.get("unread_count:user1")).thenReturn(null);
            assertThat(cacheService.getUnreadCount("user1")).isNull();
        }

        @Test
        void cacheHit_returnsCount() {
            when(valueOps.get("unread_count:user1")).thenReturn(7L);
            assertThat(cacheService.getUnreadCount("user1")).isEqualTo(7L);
        }

        @Test
        void cacheHitIntegerValue_convertsToLong() {
            when(valueOps.get("unread_count:user1")).thenReturn(3); // Integer
            assertThat(cacheService.getUnreadCount("user1")).isEqualTo(3L);
        }

        @Test
        void redisFails_returnsCacheMiss() {
            when(valueOps.get(anyString()))
                    .thenThrow(new DataAccessResourceFailureException("Redis down"));
            assertThat(cacheService.getUnreadCount("user1")).isNull();
        }
    }

    // ===================================================================
    // buildNotificationCacheKey
    // ===================================================================

    @Nested
    class BuildNotificationCacheKey {

        @Test
        void nullUserId_throwsUserIdNull() {
            assertThatThrownBy(() ->
                    cacheService.buildNotificationCacheKey(null, null, null, null, null, 1, 10))
                    .hasMessage("USER_ID_NULL");
        }

        @Test
        void emptyUserId_throwsUserIdEmpty() {
            assertThatThrownBy(() ->
                    cacheService.buildNotificationCacheKey("", null, null, null, null, 1, 10))
                    .hasMessage("USER_ID_EMPTY");
        }

        @Test
        void allPresent_singleValues_returnsFullKey() {
            String key = cacheService.buildNotificationCacheKey(
                    "u1", List.of("org1"), "UNSEEN", "HIGH", List.of("ALERT"), 1, 10);
            assertThat(key).isEqualTo("notifications:u1:org1:UNSEEN:HIGH:ALERT:1:10");
        }

        @Test
        void nullOptionals_usesEmptyStrings() {
            String key = cacheService.buildNotificationCacheKey(
                    "u1", null, null, null, null, 2, 20);
            assertThat(key).isEqualTo("notifications:u1::::2:20");
        }

        @Test
        void emptyLists_treatAsMissing() {
            String key = cacheService.buildNotificationCacheKey(
                    "u1", List.of(), null, null, List.of(), 1, 10);
            assertThat(key).isEqualTo("notifications:u1::::1:10");
        }

        @Test
        void multipleOrgIds_sortedBeforeJoining() {
            String key1 = cacheService.buildNotificationCacheKey(
                    "u1", List.of("orgB", "orgA"), null, null, null, 1, 10);
            String key2 = cacheService.buildNotificationCacheKey(
                    "u1", List.of("orgA", "orgB"), null, null, null, 1, 10);
            assertThat(key1).isEqualTo(key2);
            assertThat(key1).isEqualTo("notifications:u1:orgA,orgB::::1:10");
        }

        @Test
        void multipleTypes_sortedBeforeJoining() {
            String key1 = cacheService.buildNotificationCacheKey(
                    "u1", null, null, null, List.of("COMMENT", "ALERT"), 1, 10);
            String key2 = cacheService.buildNotificationCacheKey(
                    "u1", null, null, null, List.of("ALERT", "COMMENT"), 1, 10);
            assertThat(key1).isEqualTo(key2);
            assertThat(key1).isEqualTo("notifications:u1:::ALERT,COMMENT:1:10");
        }

        @Test
        void deterministicForSameInputs() {
            String k1 = cacheService.buildNotificationCacheKey("u1", List.of("org"), null, "T", null, 1, 5);
            String k2 = cacheService.buildNotificationCacheKey("u1", List.of("org"), null, "T", null, 1, 5);
            assertThat(k1).isEqualTo(k2);
        }
    }

    // ===================================================================
    // saveNotifications
    // ===================================================================

    @Nested
    class SaveNotifications {

        @Test
        void happyPath_callsRedisSet() {
            PageResponse<NotificationResponse> page = new PageResponse<>();
            assertThatNoException()
                    .isThrownBy(() -> cacheService.saveNotifications("some-key", page));
            verify(valueOps).set(eq("some-key"), eq(page), any());
        }

        @Test
        void redisFails_doesNotPropagate() {
            doThrow(new DataAccessResourceFailureException("down"))
                    .when(valueOps).set(any(), any(), any());
            assertThatNoException()
                    .isThrownBy(() -> cacheService.saveNotifications("key", new PageResponse<>()));
        }
    }

    // ===================================================================
    // getNotifications
    // ===================================================================

    @Nested
    class GetNotifications {

        @Test
        void cacheMiss_returnsNull() {
            when(valueOps.get("key")).thenReturn(null);
            assertThat(cacheService.getNotifications("key")).isNull();
        }

        @Test
        void cacheHit_convertsAndReturns() {
            Object raw = new Object();
            PageResponse<NotificationResponse> expected = new PageResponse<>();
            when(valueOps.get("key")).thenReturn(raw);
            when(objectMapper.convertValue(eq(raw), any(TypeReference.class))).thenReturn(expected);
            assertThat(cacheService.getNotifications("key")).isEqualTo(expected);
        }

        @Test
        void redisFails_returnsNull() {
            when(valueOps.get(any()))
                    .thenThrow(new DataAccessResourceFailureException("down"));
            assertThat(cacheService.getNotifications("key")).isNull();
        }

        @Test
        void objectMapperConversionFails_returnsNull() {
            when(valueOps.get("key")).thenReturn(new Object());
            when(objectMapper.convertValue(any(), any(TypeReference.class)))
                    .thenThrow(new IllegalArgumentException("conversion failed"));
            assertThat(cacheService.getNotifications("key")).isNull();
        }
    }

    // ===================================================================
    // invalidateUserCache
    // ===================================================================

    @Nested
    class InvalidateUserCache {

        @SuppressWarnings("unchecked")
        @Test
        void happyPath_deletesUnreadCountKeyAndScansNotificationKeys() {
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn("notifications:user1:org1:UNSEEN:HIGH:ALERT:1:10");
            when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

            assertThatNoException()
                    .isThrownBy(() -> cacheService.invalidateUserCache("user1"));

            verify(redisTemplate).delete("unread_count:user1");
            verify(redisTemplate).scan(any(ScanOptions.class));
            verify(redisTemplate).delete(any(java.util.Collection.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void foundNotificationKeys_deletesAll() {
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenReturn(true, true, false);
            when(cursor.next()).thenReturn("notifications:user1:k1", "notifications:user1:k2");
            when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

            cacheService.invalidateUserCache("user1");

            verify(redisTemplate).delete(any(java.util.Collection.class));
        }

        @Test
        void nullUserId_throwsUserIdNull() {
            assertThatThrownBy(() -> cacheService.invalidateUserCache(null))
                    .hasMessage("USER_ID_NULL");
        }

        @Test
        void emptyUserId_throwsUserIdEmpty() {
            assertThatThrownBy(() -> cacheService.invalidateUserCache(""))
                    .hasMessage("USER_ID_EMPTY");
        }

        @Test
        void redisDeleteFails_doesNotPropagate() {
            when(redisTemplate.delete(anyString()))
                    .thenThrow(new DataAccessResourceFailureException("down"));
            // Should not throw even when unread key delete fails
            assertThatNoException()
                    .isThrownBy(() -> cacheService.invalidateUserCache("user1"));
        }

        @Test
        void redisScanFails_doesNotPropagate() {
            when(redisTemplate.scan(any()))
                    .thenThrow(new DataAccessResourceFailureException("down"));
            assertThatNoException()
                    .isThrownBy(() -> cacheService.invalidateUserCache("user1"));
        }

        @Test
        void partialFailure_deleteSucceedsButScanFails_doesNotPropagate() {
            when(redisTemplate.scan(any()))
                    .thenThrow(new DataAccessResourceFailureException("down"));
            // delete of unread key should still have been attempted
            assertThatNoException()
                    .isThrownBy(() -> cacheService.invalidateUserCache("user1"));
            verify(redisTemplate).delete("unread_count:user1");
        }
    }
}
