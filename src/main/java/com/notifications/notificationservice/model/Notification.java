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
@Document(collection = "notifications")
public class Notification {

    @Id
    private String id;

    @Field("broadcast_id")
    private String broadcastId;

    @Field("recipient_id")
    private String recipientId;

    @Field("organization_id")
    private String organizationId;

    private String tier;
    private String type;
    private String title;
    private String description;

    @Field("redirect_uri")
    private String redirectUri;

    @Field("image_url")
    private String imageUrl;

    private Map<String, Object> metadata;

    @Builder.Default
    private String state = "UNSEEN";

    @Field("created_at")
    private Instant createdAt;

    private Instant seenAt;
    private Instant clickedAt;
}
