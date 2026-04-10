package com.notifications.notificationservice.service;

import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.exception.NotFoundException;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.model.UserNotificationCount;
import com.notifications.notificationservice.repository.NotificationRepository;
import com.notifications.notificationservice.repository.UserNotificationCountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock UserNotificationCountRepository userNotificationCountRepository;
    @Mock MongoTemplate mongoTemplate;
    @Mock CacheService cacheService;
    @Mock SseService sseService;

    @InjectMocks NotificationService notificationService;

    private Notification unseenNotification;
    private Notification seenNotification;
    private Notification clickedNotification;

    @BeforeEach
    void setUp() {
        unseenNotification = Notification.builder()
                .id("n1").recipientId("user1").organizationId("org1")
                .tier("HIGH").type("ALERT").title("Title").description("Desc")
                .state("UNSEEN").createdAt(Instant.now()).build();

        seenNotification = Notification.builder()
                .id("n2").recipientId("user1")
                .state("SEEN").createdAt(Instant.now()).build();

        clickedNotification = Notification.builder()
                .id("n3").recipientId("user1")
                .state("CLICKED").createdAt(Instant.now()).build();
    }

    // ===================================================================
    // getNotifications
    // ===================================================================

    @Nested
    class GetNotifications {

        @Test
        void cacheHit_returnsCachedPageWithoutHittingMongo() {
            PageResponse<NotificationResponse> cached = new PageResponse<>();
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("cache-key");
            when(cacheService.getNotifications("cache-key")).thenReturn(cached);

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications("user1", null, null, null, null, 1, 10);

            assertThat(result).isSameAs(cached);
            verifyNoInteractions(mongoTemplate);
        }

        @Test
        void cacheMiss_queriesMongo_cachesResult() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("cache-key");
            when(cacheService.getNotifications("cache-key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(1L);
            doReturn(List.of(seenNotification)).when(mongoTemplate).find(any(Query.class), any(Class.class));
            when(userNotificationCountRepository.findById("user1"))
                    .thenReturn(Optional.of(UserNotificationCount.builder().userId("user1").count(0L).build()));

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications("user1", null, null, null, null, 1, 10);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(cacheService).saveNotifications(eq("cache-key"), any());
        }

        @Test
        void unseenNotifications_areMarkedSeen() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(1L);
            doReturn(List.of(unseenNotification)).when(mongoTemplate).find(any(Query.class), any(Class.class));
            when(notificationRepository.saveAll(any())).thenReturn(List.of(unseenNotification));
            when(userNotificationCountRepository.findById("user1")).thenReturn(Optional.empty());

            notificationService.getNotifications("user1", null, null, null, null, 1, 10);

            verify(notificationRepository).saveAll(any());
            assertThat(unseenNotification.getState()).isEqualTo("SEEN");
        }

        @Test
        void multipleFilters_combinesAllInQuery() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(0L);
            doReturn(List.of()).when(mongoTemplate).find(any(Query.class), any(Class.class));
            when(userNotificationCountRepository.findById(anyString())).thenReturn(Optional.empty());

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications("user1", "org1", "UNSEEN", "HIGH", "ALERT", 1, 10);

            assertThat(result).isNotNull();
        }

        @Test
        void invalidPageValue_throwsIllegalArgument() {
            assertThatThrownBy(() ->
                    notificationService.getNotifications("user1", null, null, null, null, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("page must be");
        }

        @Test
        void cacheInvalidationFailsAfterSeenUpdate_doesNotPropagate() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(1L);
            doReturn(List.of(unseenNotification)).when(mongoTemplate).find(any(Query.class), any(Class.class));
            when(notificationRepository.saveAll(any())).thenReturn(List.of());
            doThrow(new RuntimeException("Redis down"))
                    .when(cacheService).invalidateUserCache("user1");
            when(userNotificationCountRepository.findById("user1")).thenReturn(Optional.empty());

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications("user1", null, null, null, null, 1, 10);
            assertThat(result).isNotNull();
        }

        @Test
        void mongoDown_propagatesDataAccessException() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class)))
                    .thenThrow(new DataAccessException("Mongo down") {});

            assertThatThrownBy(() ->
                    notificationService.getNotifications("user1", null, null, null, null, 1, 10))
                    .isInstanceOf(DataAccessException.class);
        }

        // FIX 9: Stable sort — compound Sort.by(desc created_at, asc _id) must not throw
        @Test
        void stableSort_doesNotThrow() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(0L);
            doReturn(List.of()).when(mongoTemplate).find(any(Query.class), any(Class.class));
            when(userNotificationCountRepository.findById(anyString())).thenReturn(Optional.empty());

            assertThatNoException().isThrownBy(() ->
                    notificationService.getNotifications("user1", null, null, null, null, 1, 10));
        }

        // FIX 10: Archive fallback — page > 1, live empty → queries archive
        @Test
        void page2EmptyFromLive_fallsBackToArchive() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(2), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            // Live collection: empty
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(0L);
            doReturn(List.of()).when(mongoTemplate).find(any(Query.class), any(Class.class));
            // Archive collection: has content
            when(mongoTemplate.count(any(Query.class), any(Class.class), eq("notifications_archive")))
                    .thenReturn(1L);
            doReturn(List.of(seenNotification)).when(mongoTemplate)
                    .find(any(Query.class), any(Class.class), eq("notifications_archive"));
            when(userNotificationCountRepository.findById("user1")).thenReturn(Optional.empty());

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications("user1", null, null, null, null, 2, 10);

            assertThat(result.getSource()).isEqualTo("archive");
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        void page1EmptyFromLive_doesNotFallBackToArchive() {
            when(cacheService.buildNotificationCacheKey(anyString(), any(), any(), any(), any(), eq(1), eq(10)))
                    .thenReturn("key");
            when(cacheService.getNotifications("key")).thenReturn(null);
            when(mongoTemplate.count(any(Query.class), any(Class.class))).thenReturn(0L);
            doReturn(List.of()).when(mongoTemplate).find(any(Query.class), any(Class.class));
            when(userNotificationCountRepository.findById(anyString())).thenReturn(Optional.empty());

            PageResponse<NotificationResponse> result =
                    notificationService.getNotifications("user1", null, null, null, null, 1, 10);

            // No archive queries with 3-arg signatures
            verify(mongoTemplate, never()).count(any(Query.class), any(Class.class), anyString());
            assertThat(result.getSource()).isNull();
        }
    }

    // ===================================================================
    // markAsClicked  (FIX 5: userId parameter + ownership check)
    // ===================================================================

    @Nested
    class MarkAsClicked {

        @Test
        void notificationNotFound_throwsNotFoundException() {
            when(notificationRepository.findById("missing")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> notificationService.markAsClicked("missing", "user1"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void wrongOwner_throwsNotFoundException() {
            // Notification belongs to user1 — user2 should not see it
            when(notificationRepository.findById("n1")).thenReturn(Optional.of(unseenNotification));

            assertThatThrownBy(() -> notificationService.markAsClicked("n1", "user2"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("n1");
        }

        @Test
        void alreadyClicked_returnsResponseWithoutSaving() {
            when(notificationRepository.findById("n3")).thenReturn(Optional.of(clickedNotification));

            NotificationResponse response = notificationService.markAsClicked("n3", "user1");

            assertThat(response.getState()).isEqualTo("CLICKED");
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void happyPath_stateChangedAndCacheInvalidated() {
            when(notificationRepository.findById("n1")).thenReturn(Optional.of(unseenNotification));
            when(notificationRepository.save(any())).thenReturn(unseenNotification);

            NotificationResponse response = notificationService.markAsClicked("n1", "user1");

            assertThat(response).isNotNull();
            verify(notificationRepository).save(any());
            verify(cacheService).invalidateUserCache("user1");
        }

        @Test
        void cacheInvalidationFails_doesNotPropagate() {
            when(notificationRepository.findById("n1")).thenReturn(Optional.of(unseenNotification));
            when(notificationRepository.save(any())).thenReturn(unseenNotification);
            doThrow(new RuntimeException("Redis down"))
                    .when(cacheService).invalidateUserCache(anyString());

            NotificationResponse response = notificationService.markAsClicked("n1", "user1");
            assertThat(response).isNotNull();
        }

        @Test
        void mongoDown_propagatesDataAccessException() {
            when(notificationRepository.findById("n1"))
                    .thenThrow(new DataAccessException("Mongo down") {});
            assertThatThrownBy(() -> notificationService.markAsClicked("n1", "user1"))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // ===================================================================
    // getUnreadCount  (FIX 6: reads from UserNotificationCount)
    // ===================================================================

    @Nested
    class GetUnreadCount {

        @Test
        void cacheHit_returnsCountWithoutHittingMongo() {
            when(cacheService.getUnreadCount("user1")).thenReturn(5L);

            long count = notificationService.getUnreadCount("user1");

            assertThat(count).isEqualTo(5L);
            verifyNoInteractions(notificationRepository);
            verifyNoInteractions(userNotificationCountRepository);
        }

        @Test
        void cacheMiss_readsFromUserNotificationCountAndCaches() {
            when(cacheService.getUnreadCount("user1")).thenReturn(null);
            when(userNotificationCountRepository.findById("user1"))
                    .thenReturn(Optional.of(UserNotificationCount.builder().userId("user1").count(3L).build()));

            long count = notificationService.getUnreadCount("user1");

            assertThat(count).isEqualTo(3L);
            verify(cacheService).saveUnreadCount("user1", 3L);
        }

        @Test
        void cacheMiss_counterDocumentNotFound_returnsZero() {
            when(cacheService.getUnreadCount("user1")).thenReturn(null);
            when(userNotificationCountRepository.findById("user1")).thenReturn(Optional.empty());

            long count = notificationService.getUnreadCount("user1");

            assertThat(count).isEqualTo(0L);
            verify(cacheService).saveUnreadCount("user1", 0L);
        }

        @Test
        void cacheSaveFails_doesNotPropagate() {
            when(cacheService.getUnreadCount("user1")).thenReturn(null);
            when(userNotificationCountRepository.findById("user1"))
                    .thenReturn(Optional.of(UserNotificationCount.builder().userId("user1").count(2L).build()));
            doThrow(new RuntimeException("Redis down"))
                    .when(cacheService).saveUnreadCount(anyString(), anyLong());

            assertThat(notificationService.getUnreadCount("user1")).isEqualTo(2L);
        }

        @Test
        void mongoDown_propagatesDataAccessException() {
            when(cacheService.getUnreadCount("user1")).thenReturn(null);
            when(userNotificationCountRepository.findById("user1"))
                    .thenThrow(new DataAccessException("Mongo down") {});
            assertThatThrownBy(() -> notificationService.getUnreadCount("user1"))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // ===================================================================
    // getBroadcastStats
    // ===================================================================

    @Nested
    class GetBroadcastStats {

        @Test
        void happyPath_aggregatesCorrectCounts() {
            Notification seen = Notification.builder().id("1").recipientId("u1")
                    .state("SEEN").build();
            Notification clicked = Notification.builder().id("2").recipientId("u2")
                    .state("CLICKED").build();
            Notification unseen = Notification.builder().id("3").recipientId("u3")
                    .state("UNSEEN").build();

            when(notificationRepository.findByBroadcastId("b1"))
                    .thenReturn(List.of(seen, clicked, unseen));

            var stats = notificationService.getBroadcastStats("b1");

            assertThat(stats.getTotalSent()).isEqualTo(3);
            assertThat(stats.getTotalSeen()).isEqualTo(2);
            assertThat(stats.getTotalClicked()).isEqualTo(1);
            assertThat(stats.getRecipients()).hasSize(3);
        }

        @Test
        void noBroadcastFound_returnsZeroStats() {
            when(notificationRepository.findByBroadcastId("missing")).thenReturn(List.of());

            var stats = notificationService.getBroadcastStats("missing");

            assertThat(stats.getTotalSent()).isZero();
            assertThat(stats.getTotalSeen()).isZero();
            assertThat(stats.getTotalClicked()).isZero();
        }

        @Test
        void mongoDown_propagatesDataAccessException() {
            when(notificationRepository.findByBroadcastId("b1"))
                    .thenThrow(new DataAccessException("Mongo down") {});
            assertThatThrownBy(() -> notificationService.getBroadcastStats("b1"))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    // ===================================================================
    // mapToResponse
    // ===================================================================

    @Test
    void mapToResponse_mapsAllFields() {
        Instant now = Instant.now();
        Notification n = Notification.builder()
                .id("id1").broadcastId("b1").recipientId("r1").organizationId("o1")
                .tier("HIGH").type("ALERT").title("T").description("D")
                .state("SEEN").createdAt(now).seenAt(now).clickedAt(now)
                .build();

        NotificationResponse r = notificationService.mapToResponse(n);

        assertThat(r.getId()).isEqualTo("id1");
        assertThat(r.getBroadcastId()).isEqualTo("b1");
        assertThat(r.getRecipientId()).isEqualTo("r1");
        assertThat(r.getTier()).isEqualTo("HIGH");
        assertThat(r.getType()).isEqualTo("ALERT");
        assertThat(r.getState()).isEqualTo("SEEN");
        assertThat(r.getCreatedAt()).isEqualTo(now);
    }
}
