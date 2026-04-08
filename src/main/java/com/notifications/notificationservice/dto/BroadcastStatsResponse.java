package com.notifications.notificationservice.dto;

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

    private String broadcastId;
    private long totalSent;
    private long totalSeen;
    private long totalClicked;
    private List<RecipientStat> recipients;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RecipientStat {
        private String userId;
        private String state;
        private Instant seenAt;
        private Instant clickedAt;
    }
}
