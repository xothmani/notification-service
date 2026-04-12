package com.notifications.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    // Each entry must be non-blank and match the userId format used in Redis keys.
    // Note: objectMapper.convertValue() does NOT trigger these annotations —
    // NotificationWorker.validatePayload() enforces the same rules programmatically.
    @NotEmpty
    @JsonProperty("recipient_ids")
    private List<
            @NotBlank(message = "recipient_ids must not contain blank entries")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                     message = "recipient_ids entries must match ^[a-zA-Z0-9_-]+$")
            String> recipientIds;

    @NotBlank
    @JsonProperty("organization_id")
    private String organizationId;

    @NotBlank
    @Pattern(regexp = "^[A-Z_]+$", message = "tier must match ^[A-Z_]+$")
    private String tier;

    @NotBlank
    @Pattern(regexp = "^[A-Z_]+$", message = "type must match ^[A-Z_]+$")
    private String type;

    // Each channel entry must be non-blank and one of the known values.
    @NotEmpty
    private List<
            @NotBlank(message = "channels must not contain blank entries")
            @Pattern(regexp = "^(IN_APP|EMAIL|SMS|PUSH)$",
                     message = "channel must be one of: IN_APP, EMAIL, SMS, PUSH")
            String> channels;

    @NotBlank
    @Size(max = 500, message = "title must not exceed 500 characters")
    private String title;

    @NotBlank
    @Size(max = 2000, message = "description must not exceed 2000 characters")
    private String description;

    @NotBlank
    @Size(max = 200, message = "redirectUri must not exceed 200 characters")
    @Pattern(regexp = "^(https?://|/).*", message = "redirectUri must be a valid absolute or relative URL")
    @JsonProperty("redirect_uri")
    private String redirectUri;

    @JsonProperty("image_url")
    private String imageUrl;

    private Map<String, Object> metadata;
}
