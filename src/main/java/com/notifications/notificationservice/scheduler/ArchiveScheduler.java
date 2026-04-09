package com.notifications.notificationservice.scheduler;

import com.mongodb.MongoBulkWriteException;
import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    private static final int BATCH_SIZE = 500;

    private final NotificationRepository notificationRepository;
    private final MongoTemplate mongoTemplate;

    // Run every day at midnight UTC
    @Scheduled(cron = "0 0 0 * * *")
    public void archiveOldNotifications() {
        log.info("Starting daily archive job...");

        // Calendar-aware subtraction — correctly handles varying month lengths
        Instant fourMonthsAgo = ZonedDateTime.now(ZoneOffset.UTC)
                .minusMonths(4)
                .toInstant();

        int totalArchived = 0;

        while (true) {
            Page<Notification> batch = notificationRepository.findByCreatedAtBefore(
                    fourMonthsAgo, PageRequest.of(0, BATCH_SIZE));

            if (!batch.hasContent()) break;

            List<Notification> toArchive = batch.getContent();

            try {
                mongoTemplate.insert(toArchive, "notifications_archive");
            } catch (DuplicateKeyException | MongoBulkWriteException e) {
                // Batch already exists in archive (e.g. previous run crashed after
                // insert but before delete). Safe to proceed with the delete step.
                log.warn("Duplicate documents detected in archive batch — " +
                        "previous run may have crashed mid-job. Continuing delete.", e);
            }

            notificationRepository.deleteAll(toArchive);
            totalArchived += toArchive.size();

            log.debug("Archived batch of {} notifications", toArchive.size());

            // Always query page 0 — deleted records are gone so the next oldest
            // documents roll up to the front of the result set automatically
        }

        if (totalArchived == 0) {
            log.info("No notifications to archive.");
        } else {
            log.info("Archive job completed — {} notifications archived.", totalArchived);
        }
    }
}
