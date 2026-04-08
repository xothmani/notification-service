package com.notifications.notificationservice.scheduler;

import com.notifications.notificationservice.model.Notification;
import com.notifications.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

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

        // Use calendar-aware subtraction so month lengths are respected
        Instant fourMonthsAgo = ZonedDateTime.now(ZoneOffset.UTC)
                .minusMonths(4)
                .toInstant();

        int totalArchived = 0;
        Page<Notification> batch;

        // Process in batches to avoid loading the entire dataset into heap
        do {
            batch = notificationRepository.findByCreatedAtBefore(
                    fourMonthsAgo, PageRequest.of(0, BATCH_SIZE));

            if (!batch.hasContent()) break;

            mongoTemplate.insert(batch.getContent(), "notifications_archive");
            notificationRepository.deleteAll(batch.getContent());

            totalArchived += batch.getContent().size();
            log.debug("Archived batch of {} notifications", batch.getContent().size());

            // Always query page 0 — previous batch was deleted, so the next
            // oldest records are now at the front of the result set
        } while (batch.hasContent());

        if (totalArchived == 0) {
            log.info("No notifications to archive.");
        } else {
            log.info("Archive job completed. {} notifications archived.", totalArchived);
        }
    }
}