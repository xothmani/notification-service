package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.model.FailedNotification;
import com.notifications.notificationservice.repository.FailedNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlqWorkerTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock FailedNotificationRepository failedNotificationRepository;
    @Mock ObjectMapper objectMapper;
    @Mock ListOperations<String, Object> listOps;

    @InjectMocks DlqWorker dlqWorker;

    private static final String DLQ_KEY = "notifications_dlq";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    // ===================================================================
    // Empty DLQ
    // ===================================================================

    @Test
    void emptyDlq_sweepsWithoutSavingAnything() {
        when(listOps.leftPop(DLQ_KEY)).thenReturn(null);

        dlqWorker.sweepDlq();

        verify(failedNotificationRepository, never()).save(any());
    }

    // ===================================================================
    // Redis poll fails
    // ===================================================================

    @Test
    void redisPollFails_sweepAbortsGracefully() {
        when(listOps.leftPop(DLQ_KEY))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThatNoException().isThrownBy(() -> dlqWorker.sweepDlq());
        verify(failedNotificationRepository, never()).save(any());
    }

    // ===================================================================
    // Happy path — single job
    // ===================================================================

    @Test
    void singleJobInDlq_savedToMongoAndConsumed() throws Exception {
        Object job = Map.of("key", "value");
        when(listOps.leftPop(DLQ_KEY))
                .thenReturn(job)
                .thenReturn(null); // empty on second call
        when(objectMapper.convertValue(job, Map.class)).thenReturn(Map.of("key", "value"));
        when(failedNotificationRepository.save(any())).thenReturn(new FailedNotification());

        dlqWorker.sweepDlq();

        verify(failedNotificationRepository, times(1)).save(any(FailedNotification.class));
        // Must NOT be re-queued since save succeeded
        verify(listOps, never()).rightPush(eq(DLQ_KEY), any());
    }

    // ===================================================================
    // MongoDB save failure → re-queue to DLQ tail
    // ===================================================================

    @Test
    void mongoSaveFails_jobReQueuedToDlqTail() throws Exception {
        Object job = Map.of("key", "value");
        when(listOps.leftPop(DLQ_KEY)).thenReturn(job);
        when(objectMapper.convertValue(job, Map.class)).thenReturn(Map.of("key", "value"));
        when(failedNotificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo down"));

        dlqWorker.sweepDlq();

        // Job is pushed back to DLQ tail so it's not lost
        verify(listOps).rightPush(eq(DLQ_KEY), eq(job));
    }

    @Test
    void mongoSaveFails_sweepAbortsAfterReQueue() throws Exception {
        // First job fails → requeued → sweep stops (likely Mongo is down)
        Object job1 = Map.of("k", "v1");
        Object job2 = Map.of("k", "v2");
        when(listOps.leftPop(DLQ_KEY)).thenReturn(job1).thenReturn(job2);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(Map.of());
        when(failedNotificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo down"));

        dlqWorker.sweepDlq();

        // Only the first job was processed — sweep returned after MongoDB failure
        verify(failedNotificationRepository, times(1)).save(any());
        // The second job was never popped
        verify(listOps, times(1)).leftPop(DLQ_KEY);
    }

    // ===================================================================
    // MongoDB fails AND re-queue also fails → logged but not propagated
    // ===================================================================

    @Test
    void mongoSaveFailsAndReQueueFails_doesNotPropagate() throws Exception {
        Object job = Map.of("k", "v");
        when(listOps.leftPop(DLQ_KEY)).thenReturn(job);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(Map.of());
        when(failedNotificationRepository.save(any()))
                .thenThrow(new RuntimeException("Mongo down"));
        doThrow(new DataAccessResourceFailureException("Redis also down"))
                .when(listOps).rightPush(eq(DLQ_KEY), any());

        assertThatNoException().isThrownBy(() -> dlqWorker.sweepDlq());
    }

    // ===================================================================
    // Unconvertible job → stored as raw string map, never dropped
    // ===================================================================

    @Test
    void unconvertibleJob_storedAsRawStringMap() throws Exception {
        Object weirdJob = "plain-string-not-a-map";
        when(listOps.leftPop(DLQ_KEY))
                .thenReturn(weirdJob)
                .thenReturn(null);
        when(objectMapper.convertValue(weirdJob, Map.class))
                .thenThrow(new IllegalArgumentException("not a map"));
        when(failedNotificationRepository.save(any())).thenReturn(new FailedNotification());

        dlqWorker.sweepDlq();

        // Job is still saved (with raw string representation) — never silently dropped
        verify(failedNotificationRepository, times(1)).save(any(FailedNotification.class));
    }

    // ===================================================================
    // MAX_PER_SWEEP cap (100 jobs max per execution)
    // ===================================================================

    @Test
    void moreThan100JobsInDlq_stopsAfter100() throws Exception {
        // Always returns a job — simulates infinite DLQ
        when(listOps.leftPop(DLQ_KEY)).thenReturn(Map.of("k", "v"));
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(Map.of("k", "v"));
        when(failedNotificationRepository.save(any())).thenReturn(new FailedNotification());

        dlqWorker.sweepDlq();

        // Must stop at 100 — not run indefinitely
        verify(failedNotificationRepository, times(100)).save(any());
        verify(listOps, times(100)).leftPop(DLQ_KEY);
    }

    // ===================================================================
    // Multiple jobs — all processed in a single sweep
    // ===================================================================

    @Test
    void multipleJobsInDlq_allProcessed() throws Exception {
        Object job1 = Map.of("k", "v1");
        Object job2 = Map.of("k", "v2");
        Object job3 = Map.of("k", "v3");
        when(listOps.leftPop(DLQ_KEY))
                .thenReturn(job1)
                .thenReturn(job2)
                .thenReturn(job3)
                .thenReturn(null);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(Map.of());
        when(failedNotificationRepository.save(any())).thenReturn(new FailedNotification());

        dlqWorker.sweepDlq();

        verify(failedNotificationRepository, times(3)).save(any());
    }
}
