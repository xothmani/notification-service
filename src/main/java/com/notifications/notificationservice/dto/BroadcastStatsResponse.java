package com.notifications.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BroadcastStatsResponse {

    @JsonProperty("broadcast_id")
    private String broadcastId;

    @JsonProperty("total_sent")
    private long totalSent;

    @JsonProperty("total_seen")
    private long totalSeen;

    @JsonProperty("total_clicked")
    private long totalClicked;

    private List<RecipientStat> recipients;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RecipientStat {

        @JsonProperty("user_id")
        private String userId;

        private String state;

        @JsonProperty("seen_at")
        private Instant seenAt;

        @JsonProperty("clicked_at")
        private Instant clickedAt;
    }
}
