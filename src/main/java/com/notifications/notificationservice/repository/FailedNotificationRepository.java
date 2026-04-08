package com.notifications.notificationservice.repository;

import com.notifications.notificationservice.model.FailedNotification;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FailedNotificationRepository
        extends MongoRepository<FailedNotification, String> {

    // Find all failed notifications by channel
    java.util.List<FailedNotification> findByFailedChannel(
            String failedChannel
    );
}
