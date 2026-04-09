package com.notifications.notificationservice.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class NotificationResponse {

    private String id;
    private String broadcastId;
    private String recipientId;
    private String organizationId;
    private String tier;
    private String type;
    private String title;
    private String description;
    private String redirectUri;
    private String imageUrl;
    private Map<String, Object> metadata;
    private String state;
    private Instant createdAt;
    private Instant seenAt;
    private Instant clickedAt;
}
