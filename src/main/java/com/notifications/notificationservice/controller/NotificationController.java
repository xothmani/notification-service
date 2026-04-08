package com.notifications.notificationservice.controller;

import com.notifications.notificationservice.dto.ApiResponse;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.service.SseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final SseService sseService;

    // GET /api/notifications
    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> getNotifications(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z_]+$", message = "state must match ^[A-Z_]+$")
            String state,
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z_]+$", message = "tier must match ^[A-Z_]+$")
            String tier,
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z_]+$", message = "type must match ^[A-Z_]+$")
            String type,
            @RequestParam(defaultValue = "1")  @Min(1)          int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {

        PageResponse<NotificationResponse> response =
                notificationService.getNotifications(
                        userId, organizationId, state, tier, type, page, limit);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // PATCH /api/notifications/{id}/clicked
    @PatchMapping("/notifications/{notificationId}/clicked")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsClicked(
            @PathVariable String notificationId) {

        NotificationResponse response =
                notificationService.markAsClicked(notificationId);

        return ResponseEntity.ok(
                ApiResponse.success(response, "Notification marked as clicked"));
    }

    // GET /api/stream/notifications
    @GetMapping(value = "/stream/notifications",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
            @RequestHeader("X-User-Id") String userId) {

        log.info("User {} connected to SSE stream", userId);

        long unreadCount = notificationService.getUnreadCount(userId);
        return sseService.connect(userId, unreadCount);
    }
}