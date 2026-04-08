package com.notifications.notificationservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "failed_notifications")
public class FailedNotification {

    @Id
    private String id;

    @Field("original_payload")
    private Map<String, Object> originalPayload;

    @Field("failed_channel")
    private String failedChannel;

    @Field("failure_reason")
    private String failureReason;

    @Field("failed_at")
    private Instant failedAt;
}
