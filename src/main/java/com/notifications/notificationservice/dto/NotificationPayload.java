package com.notifications.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationPayload {

    @NotEmpty
    @JsonProperty("recipient_ids")
    private List<String> recipientIds;

    @NotBlank
    @JsonProperty("organization_id")
    private String organizationId;

    @NotBlank
    @Pattern(regexp = "^[A-Z_]+$", message = "tier must match ^[A-Z_]+$")
    private String tier;

    @NotBlank
    @Pattern(regexp = "^[A-Z_]+$", message = "type must match ^[A-Z_]+$")
    private String type;

    @NotEmpty
    private List<String> channels;

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotBlank
    @JsonProperty("redirect_uri")
    private String redirectUri;

    @JsonProperty("image_url")
    private String imageUrl;

    private Map<String, Object> metadata;
}