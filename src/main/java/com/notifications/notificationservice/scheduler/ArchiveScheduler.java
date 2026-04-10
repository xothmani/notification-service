package com.notifications.notificationservice.scheduler;

import com.mongodb.MongoBulkWriteException;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    private static final int BATCH_SIZE = 500;

    private final NotificationRepository notificationRepository;
    private final MongoTemplate mongoTemplate;

    // Guards against concurrent execution within the same JVM instance.
    // For multi-instance deployments add ShedLock (net.javacrumbs.shedlock)
    // and annotate this method with @SchedulerLock(name = "archiveOldNotifications").
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Run every day at midnight UTC
    @Scheduled(cron = "0 0 0 * * *")
    public void archiveOldNotifications() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Archive job is already running — skipping this trigger");
            return;
        }

        try {
            runArchive();
        } catch (Exception e) {
            log.error("CRITICAL: Archive job failed completely — notifications not archived. " +
                      "Next scheduled run will retry.", e);
        } finally {
            isRunning.set(false);
        }
    }

    private void runArchive() {
        log.info("Starting daily archive job...");

        // Calendar-aware subtraction — correctly handles varying month lengths
        Instant fourMonthsAgo = ZonedDateTime.now(ZoneOffset.UTC)
                .minusMonths(4)
                .toInstant();

        int totalArchived = 0;

        while (true) {
            Page<Notification> batch;
            try {
                batch = notificationRepository.findByCreatedAtBefore(
                        fourMonthsAgo, PageRequest.of(0, BATCH_SIZE));
            } catch (DataAccessException e) {
                log.error("MongoDB read failed during archive job after archiving {} notifications. " +
                          "Job will resume at next scheduled run.", totalArchived, e);
                return;
            }

            if (!batch.hasContent()) break;

            List<Notification> toArchive = batch.getContent();

            try {
                mongoTemplate.insert(toArchive, "notifications_archive");
            } catch (DuplicateKeyException | MongoBulkWriteException e) {
                // Batch already exists in archive (e.g. previous run crashed after
                // insert but before delete). Safe to proceed with the delete step.
                log.warn("Duplicate documents detected in archive batch — " +
                        "previous run may have crashed mid-job. Continuing delete.", e);
            } catch (DataAccessException e) {
                log.error("MongoDB insert to archive failed for batch of {}. " +
                          "Aborting archive job to preserve data integrity — " +
                          "live records will NOT be deleted.", toArchive.size(), e);
                return;
            }

            try {
                notificationRepository.deleteAll(toArchive);
            } catch (DataAccessException e) {
                log.error("MongoDB delete failed after archive insert for batch of {}. " +
                          "Documents remain in live collection — next run will attempt " +
                          "re-insert (DuplicateKeyException is handled) and re-delete.", toArchive.size(), e);
                return;
            }

            totalArchived += toArchive.size();
            log.debug("Archived batch of {} notifications", toArchive.size());

            // Always query page 0 — deleted records are gone so the next oldest
            // documents roll up to the front of the result set automatically
        }

        log.info("Archive job completed — {} notifications archived.", totalArchived);
    }
}
