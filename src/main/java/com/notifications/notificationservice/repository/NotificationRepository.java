package com.notifications.notificationservice.repository;

import com.notifications.notificationservice.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    // Used by markUnseenAsSeen to load the full unseen list for a user
    List<Notification> findByRecipientIdAndState(String recipientId, String state);

    // Used by Cache-Aside unread count
    long countByRecipientIdAndState(String recipientId, String state);

    // Used by broadcast stats endpoint
    List<Notification> findByBroadcastId(String broadcastId);

    // Used by ArchiveScheduler — paginated to avoid loading all records into heap
    Page<Notification> findByCreatedAtBefore(Instant date, Pageable pageable);
}