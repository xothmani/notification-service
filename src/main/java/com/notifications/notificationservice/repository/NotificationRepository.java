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

    // Unread count — used by Cache-Aside and worker cache refresh
    long countByRecipientIdAndState(String recipientId, String state);

    // Broadcast stats — internal endpoint
    List<Notification> findByBroadcastId(String broadcastId);

    // Archive scheduler — paginated to avoid loading the full dataset into heap
    Page<Notification> findByCreatedAtBefore(Instant date, Pageable pageable);
}
