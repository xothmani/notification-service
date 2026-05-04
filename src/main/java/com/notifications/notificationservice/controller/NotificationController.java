package com.notifications.notificationservice.controller;

import com.notifications.notificationservice.dto.ApiResponse;
import com.notifications.notificationservice.dto.GroupedNotificationResponse;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.service.SseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "User notification management")
public class NotificationController {

    private final NotificationService notificationService;
    private final SseService sseService;

    // GET /api/notifications
    @Operation(summary = "Get paginated notifications for a user")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Bad Request — missing or invalid header/param")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Service Unavailable — downstream store unreachable")
    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<?>> getNotifications(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader("X-User-Id")
            @NotBlank(message = "X-User-Id must not be blank")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "X-User-Id must match ^[a-zA-Z0-9_-]+$")
            String userId,
            @Parameter(description = "Filter by one or more organization IDs")
            @RequestParam(name = "org_ids", required = false)
            List<String> orgIds,
            @Parameter(description = "Filter by notification state",
                       schema = @Schema(allowableValues = {"UNSEEN", "SEEN", "CLICKED"}))
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z_]+$", message = "state must match ^[A-Z_]+$")
            String state,
            @Parameter(description = "Filter by notification tier. Any uppercase string with underscores.")
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Z_]+$", message = "tier must match ^[A-Z_]+$")
            String tier,
            @Parameter(description = "Filter by one or more notification types. Uppercase with underscores.")
            @RequestParam(name = "types", required = false)
            List<@Pattern(regexp = "^[A-Z_]+$", message = "each type must match ^[A-Z_]+$") String> types,
            @Parameter(description = "Include snoozed notifications (default: false)")
            @RequestParam(name = "include_snoozed", defaultValue = "false")
            boolean includeSnoozed,
            @Parameter(description = "Group results by date. Enum: date | none (default: none)",
                       schema = @Schema(allowableValues = {"date", "none"}))
            @RequestParam(name = "group_by", defaultValue = "none")
            @Pattern(regexp = "^(date|none)$", message = "group_by must be 'date' or 'none'")
            String groupBy,
            @Parameter(description = "Page number (default: 1, minimum: 1)")
            @RequestParam(defaultValue = "1")  @Min(1)           int page,
            @Parameter(description = "Page size (default: 10, minimum: 1, maximum: 100)")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {

        // include_snoozed is accepted but not yet applied — snooze filtering will be
        // added in a future feature once the snooze model is defined.

        if ("date".equals(groupBy)) {
            GroupedNotificationResponse grouped = notificationService.getGroupedNotifications(
                    userId, orgIds, state, tier, types, page, limit);
            return ResponseEntity.ok(ApiResponse.success(grouped));
        }

        PageResponse<NotificationResponse> response =
                notificationService.getNotifications(userId, orgIds, state, tier, types, page, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // PATCH /api/notifications/{id}/clicked
    @Operation(summary = "Mark a notification as clicked")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Bad Request — missing or blank header/path")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not Found — notification does not exist or belongs to another user")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Service Unavailable")
    @PatchMapping("/notifications/{notificationId}/clicked")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsClicked(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader("X-User-Id")
            @NotBlank(message = "X-User-Id must not be blank")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "X-User-Id must match ^[a-zA-Z0-9_-]+$")
            String userId,
            @Parameter(description = "ID of the notification to mark as clicked", required = true)
            @PathVariable
            @NotBlank(message = "notificationId must not be blank")
            String notificationId) {

        NotificationResponse response =
                notificationService.markAsClicked(notificationId, userId);

        return ResponseEntity.ok(
                ApiResponse.success(response, "Notification marked as clicked"));
    }

    // GET /api/stream/notifications
    @Operation(summary = "Open SSE stream for real-time notification delivery")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SSE stream established")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Bad Request — missing or invalid X-User-Id")
    @GetMapping(value = "/stream/notifications",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader("X-User-Id")
            @NotBlank(message = "X-User-Id must not be blank")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "X-User-Id must match ^[a-zA-Z0-9_-]+$")
            String userId) {

        log.info("User {} connected to SSE stream", userId);

        // Fetch unread count best-effort — a MongoDB failure must not prevent
        // the SSE connection from being established; client will receive a count
        // of 0 and can refresh on reconnect.
        long unreadCount = 0;
        try {
            unreadCount = notificationService.getUnreadCount(userId);
        } catch (Exception e) {
            log.warn("Could not fetch unread count for SSE init for user {} — defaulting to 0", userId, e);
        }

        return sseService.connect(userId, unreadCount);
    }
}
