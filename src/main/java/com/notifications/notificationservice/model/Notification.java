package com.notifications.notificationservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "notifications")
@CompoundIndex(name = "recipient_state_idx", def = "{'recipient_id': 1, 'state': 1}")
@CompoundIndex(name = "broadcast_recipient_unique_idx", def = "{'broadcast_id': 1, 'recipient_id': 1}", unique = true)
public class Notification {

    @Id
    private String id;

    @Indexed
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

    @Indexed
    @Field("created_at")
    private Instant createdAt;

    private Instant seenAt;
    private Instant clickedAt;
}
