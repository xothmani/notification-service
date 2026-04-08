package com.notifications.notificationservice.controller;

import com.notifications.notificationservice.dto.ApiResponse;
import com.notifications.notificationservice.dto.BroadcastStatsResponse;
import com.notifications.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalNotificationController {

    private final NotificationService notificationService;

    // GET /api/internal/notifications/broadcast/{id}/stats
    @GetMapping("/notifications/broadcast/{broadcastId}/stats")
    public ResponseEntity<ApiResponse<BroadcastStatsResponse>>
    getBroadcastStats(
            @PathVariable String broadcastId) {

        log.info("Getting broadcast stats for: {}", broadcastId);

        BroadcastStatsResponse stats =
                notificationService.getBroadcastStats(broadcastId);

        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
