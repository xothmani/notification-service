package com.notifications.notificationservice.controller;

import com.notifications.notificationservice.dto.ApiResponse;
import com.notifications.notificationservice.dto.BroadcastStatsResponse;
import com.notifications.notificationservice.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Internal admin endpoints — protected by internal token")
public class InternalNotificationController {

    private final NotificationService notificationService;

    // GET /api/internal/notifications/broadcast/{id}/stats
    @Operation(summary = "Get delivery stats for a broadcast")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Bad Request — blank broadcastId")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — missing or invalid internal token")
    @GetMapping("/notifications/broadcast/{broadcastId}/stats")
    public ResponseEntity<ApiResponse<BroadcastStatsResponse>> getBroadcastStats(
            @PathVariable
            @NotBlank(message = "broadcastId must not be blank")
            String broadcastId) {

        log.debug("Getting broadcast stats for: {}", broadcastId);

        BroadcastStatsResponse stats =
                notificationService.getBroadcastStats(broadcastId);

        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
