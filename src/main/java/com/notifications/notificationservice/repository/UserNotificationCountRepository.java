package com.notifications.notificationservice.repository;

import com.notifications.notificationservice.model.UserNotificationCount;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserNotificationCountRepository extends MongoRepository<UserNotificationCount, String> {
    // findById(userId) is inherited — returns Optional<UserNotificationCount>
}
