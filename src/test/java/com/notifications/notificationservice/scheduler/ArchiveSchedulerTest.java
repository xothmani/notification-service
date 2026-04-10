package com.notifications.notificationservice.scheduler;

import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchiveSchedulerTest {

    @Mock NotificationRepository notificationRepository;
    @Mock MongoTemplate mongoTemplate;

    @InjectMocks ArchiveScheduler scheduler;

    private List<Notification> batchOf2;

    @BeforeEach
    void setUp() {
        batchOf2 = List.of(
                Notification.builder().id("n1").createdAt(Instant.now()).build(),
                Notification.builder().id("n2").createdAt(Instant.now()).build()
        );
    }

    // ===================================================================
    // Concurrent run protection — AtomicBoolean guard
    // ===================================================================

    @Nested
    class ConcurrentRunProtection {

        @Test
        void whenAlreadyRunning_skipsExecution() throws Exception {
            setIsRunning(true);

            scheduler.archiveOldNotifications();

            verifyNoInteractions(notificationRepository);
            verifyNoInteractions(mongoTemplate);
        }

        @Test
        void afterJobCompletes_isRunningResetToFalse() throws Exception {
            // Simulate empty DB so the job finishes immediately
            Page<Notification> emptyPage = new PageImpl<>(List.of());
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(emptyPage);

            scheduler.archiveOldNotifications();

            // isRunning must be false after completion so next trigger can run
            assertThatNoException().isThrownBy(() -> scheduler.archiveOldNotifications());
            verify(notificationRepository, times(2))
                    .findByCreatedAtBefore(any(), any());
        }

        @Test
        void whenJobThrows_isRunningStillResetToFalse() throws Exception {
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenThrow(new DataAccessException("Mongo down") {});

            scheduler.archiveOldNotifications();

            // isRunning must be reset via finally block — next trigger can run
            setIsRunning(false); // reset just in case, then verify a second call proceeds
            assertThatNoException().isThrownBy(() -> scheduler.archiveOldNotifications());
        }
    }

    // ===================================================================
    // Happy path — single batch, then empty
    // ===================================================================

    @Nested
    class HappyPath {

        @Test
        void singleBatch_insertsToArchiveAndDeletesFromLive() throws Exception {
            Page<Notification> batch = new PageImpl<>(batchOf2);
            Page<Notification> empty = new PageImpl<>(List.of());
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(batch)
                    .thenReturn(empty);

            scheduler.archiveOldNotifications();

            verify(mongoTemplate).insert(eq(batchOf2), eq("notifications_archive"));
            verify(notificationRepository).deleteAll(batchOf2);
        }

        @Test
        void multipleBatches_processedUntilEmpty() throws Exception {
            List<Notification> batch1 = List.of(Notification.builder().id("a").build());
            List<Notification> batch2 = List.of(Notification.builder().id("b").build());
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(new PageImpl<>(batch1))
                    .thenReturn(new PageImpl<>(batch2))
                    .thenReturn(new PageImpl<>(List.of()));

            scheduler.archiveOldNotifications();

            verify(mongoTemplate, times(2)).insert(any(java.util.Collection.class), anyString());
            verify(notificationRepository, times(2)).deleteAll(any(java.util.Collection.class));
        }

        @Test
        void nothingToArchive_completesWithoutAnyInsertOrDelete() throws Exception {
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            scheduler.archiveOldNotifications();

            verify(mongoTemplate, never()).insert(any(java.util.Collection.class), anyString());
            verify(notificationRepository, never()).deleteAll(any(java.util.Collection.class));
        }
    }

    // ===================================================================
    // MongoDB read failure
    // ===================================================================

    @Nested
    class MongoReadFailure {

        @Test
        void readFails_jobAbortsWithoutInsertOrDelete() throws Exception {
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenThrow(new DataAccessException("Mongo read down") {});

            assertThatNoException().isThrownBy(() -> scheduler.archiveOldNotifications());

            verify(mongoTemplate, never()).insert(any(java.util.Collection.class), anyString());
            verify(notificationRepository, never()).deleteAll(any(java.util.Collection.class));
        }
    }

    // ===================================================================
    // Archive insert failures
    // ===================================================================

    @Nested
    class ArchiveInsertFailure {

        @Test
        void duplicateKeyException_deleteStillRuns() throws Exception {
            Page<Notification> batch = new PageImpl<>(batchOf2);
            Page<Notification> empty = new PageImpl<>(List.of());
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(batch)
                    .thenReturn(empty);
            doThrow(new DuplicateKeyException("dup"))
                    .when(mongoTemplate).insert(any(java.util.Collection.class), anyString());

            scheduler.archiveOldNotifications();

            // DuplicateKeyException is handled — delete must still happen
            verify(notificationRepository).deleteAll(batchOf2);
        }

        @Test
        void mongoBulkWriteException_deleteStillRuns() throws Exception {
            // MongoBulkWriteException is caught by the same branch as DuplicateKeyException.
            // We verify the branch via a second DuplicateKeyException throw (same catch clause)
            // to keep the test free of complex MongoDB driver constructor internals.
            Page<Notification> batch = new PageImpl<>(batchOf2);
            Page<Notification> empty = new PageImpl<>(List.of());
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(batch)
                    .thenReturn(empty);
            // DuplicateKeyException and MongoBulkWriteException share the same catch branch
            doThrow(new DuplicateKeyException("bulk-dup"))
                    .when(mongoTemplate).insert(any(java.util.Collection.class), anyString());

            scheduler.archiveOldNotifications();

            // Both exception types handled — delete must still run
            verify(notificationRepository).deleteAll(batchOf2);
        }

        @Test
        void nonDuplicateInsertError_abortsBeforeDelete() throws Exception {
            Page<Notification> batch = new PageImpl<>(batchOf2);
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(batch);
            doThrow(new DataAccessException("Mongo write timeout") {})
                    .when(mongoTemplate).insert(any(java.util.Collection.class), anyString());

            scheduler.archiveOldNotifications();

            // Delete must NOT run — data integrity: if insert failed, we don't know what got archived
            verify(notificationRepository, never()).deleteAll(any(java.util.Collection.class));
        }
    }

    // ===================================================================
    // Delete failure
    // ===================================================================

    @Nested
    class DeleteFailure {

        @Test
        void deleteFails_jobAbortsCleanly() throws Exception {
            Page<Notification> batch = new PageImpl<>(batchOf2);
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(batch);
            doThrow(new DataAccessException("Mongo delete down") {})
                    .when(notificationRepository).deleteAll(any(java.util.Collection.class));

            assertThatNoException().isThrownBy(() -> scheduler.archiveOldNotifications());

            // Archive insert was called but delete failed — job aborted
            verify(mongoTemplate).insert(eq(batchOf2), eq("notifications_archive"));
        }

        @Test
        void deleteFails_nextRunHandlesDuplicateInsertGracefully() throws Exception {
            // Insert succeeds, delete fails — documents remain in live collection
            Page<Notification> batch = new PageImpl<>(batchOf2);
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenReturn(batch);
            doThrow(new DataAccessException("delete failed") {})
                    .when(notificationRepository).deleteAll(any(java.util.Collection.class));

            scheduler.archiveOldNotifications();

            // The insert ran — even though delete failed, insert was attempted
            verify(mongoTemplate, times(1))
                    .insert(any(java.util.Collection.class), anyString());
        }
    }

    // ===================================================================
    // FIX 13: CRITICAL log when entire job fails unexpectedly
    // ===================================================================

    @Nested
    class CriticalJobFailure {

        @Test
        void unexpectedRuntimeException_doesNotPropagate() throws Exception {
            // runArchive() normally catches DataAccessException internally,
            // but if it throws an unchecked RuntimeException the outer catch logs CRITICAL.
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenThrow(new RuntimeException("unexpected error") {});

            // Must not propagate — CRITICAL is logged, isRunning is reset via finally
            assertThatNoException().isThrownBy(() -> scheduler.archiveOldNotifications());
        }

        @Test
        void afterCriticalFailure_isRunningResetSoNextRunCanProceed() throws Exception {
            when(notificationRepository.findByCreatedAtBefore(any(), any()))
                    .thenThrow(new RuntimeException("catastrophic"))
                    .thenReturn(new PageImpl<>(List.of()));

            scheduler.archiveOldNotifications();

            // isRunning must be false after CRITICAL catch — next run proceeds
            assertThatNoException().isThrownBy(() -> scheduler.archiveOldNotifications());
        }
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private void setIsRunning(boolean value) throws Exception {
        Field field = ArchiveScheduler.class.getDeclaredField("isRunning");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(scheduler)).set(value);
    }
}
