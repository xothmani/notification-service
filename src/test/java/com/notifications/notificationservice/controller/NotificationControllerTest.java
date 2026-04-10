package com.notifications.notificationservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationResponse;
import com.notifications.notificationservice.dto.PageResponse;
import com.notifications.notificationservice.exception.GlobalExceptionHandler;
import com.notifications.notificationservice.exception.NotFoundException;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.service.SseService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = NotificationController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class}
)
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean NotificationService notificationService;
    @MockBean SseService sseService;

    private static final String BASE_URL    = "/api/notifications";
    private static final String CLICKED_URL = "/api/notifications/{id}/clicked";
    private static final String SSE_URL     = "/api/stream/notifications";

    // ===================================================================
    // GET /api/notifications
    // ===================================================================

    @Nested
    class GetNotifications {

        @Test
        void missingXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("ERROR"));
        }

        @Test
        void blankXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL).header("X-User-Id", ""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("ERROR"));
        }

        @Test
        void invalidFormatXUserIdHeader_returns400() throws Exception {
            // @Pattern on controller rejects "user:id" with a ConstraintViolationException → 400
            mockMvc.perform(get(BASE_URL).header("X-User-Id", "user:with:colons"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void happyPath_returns200WithPageResponse() throws Exception {
            PageResponse<NotificationResponse> page = PageResponse.<NotificationResponse>builder()
                    .content(List.of())
                    .totalElements(0)
                    .totalPages(0)
                    .pageNumber(1)
                    .pageSize(10)
                    .build();
            when(notificationService.getNotifications(
                    anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(page);

            mockMvc.perform(get(BASE_URL).header("X-User-Id", "user1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.total_elements").value(0));
        }

        @Test
        void withAllFilters_returns200() throws Exception {
            PageResponse<NotificationResponse> page = PageResponse.<NotificationResponse>builder()
                    .content(List.of()).totalElements(0).totalPages(0).pageNumber(1).pageSize(10)
                    .build();
            when(notificationService.getNotifications(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(page);

            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("organizationId", "org1")
                            .param("state", "UNSEEN")
                            .param("tier", "HIGH")
                            .param("type", "ALERT"))
                    .andExpect(status().isOk());
        }

        @Test
        void pageZero_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("page", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void limitOverMax_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("limit", "101"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void invalidStateFormat_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("state", "unseen-invalid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void pageNotANumber_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("page", "abc"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void pageNegative_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("page", "-1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void limitZero_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header("X-User-Id", "user1")
                            .param("limit", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void userIdInvalidFormat_serviceThrows_returns422() throws Exception {
            // Tests the GlobalExceptionHandler 422 path:
            // IllegalArgumentException("USER_ID_INVALID_FORMAT") → 422 Unprocessable Entity
            when(notificationService.getNotifications(
                    anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalArgumentException("USER_ID_INVALID_FORMAT"));

            mockMvc.perform(get(BASE_URL).header("X-User-Id", "user1"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value("ERROR"));
        }

        @Test
        void mongoDown_returns503() throws Exception {
            when(notificationService.getNotifications(
                    anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new DataAccessException("Mongo down") {});

            mockMvc.perform(get(BASE_URL).header("X-User-Id", "user1"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("ERROR"));
        }
    }

    // ===================================================================
    // PATCH /api/notifications/{id}/clicked
    // ===================================================================

    @Nested
    class MarkAsClicked {

        @Test
        void happyPath_returns200() throws Exception {
            NotificationResponse response = NotificationResponse.builder()
                    .id("n1").state("CLICKED").build();
            when(notificationService.markAsClicked("n1", "user1")).thenReturn(response);

            mockMvc.perform(patch(CLICKED_URL, "n1").header("X-User-Id", "user1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("CLICKED"));
        }

        @Test
        void missingXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(patch(CLICKED_URL, "n1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void blankXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(patch(CLICKED_URL, "n1").header("X-User-Id", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void notificationNotFound_returns404() throws Exception {
            when(notificationService.markAsClicked("missing", "user1"))
                    .thenThrow(new NotFoundException("Notification not found: missing"));

            mockMvc.perform(patch(CLICKED_URL, "missing").header("X-User-Id", "user1"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void wrongOwner_returns404() throws Exception {
            // FIX 5: ownership check — service throws NotFoundException when caller is not the owner
            when(notificationService.markAsClicked("n1", "user2"))
                    .thenThrow(new NotFoundException("Notification not found: n1"));

            mockMvc.perform(patch(CLICKED_URL, "n1").header("X-User-Id", "user2"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void blankNotificationId_returns400() throws Exception {
            // Space-encoded path variable triggers @NotBlank → ConstraintViolationException → 400
            mockMvc.perform(patch("/api/notifications/ /clicked").header("X-User-Id", "user1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void mongoDown_returns503() throws Exception {
            when(notificationService.markAsClicked(anyString(), anyString()))
                    .thenThrow(new DataAccessException("Mongo down") {});

            mockMvc.perform(patch(CLICKED_URL, "n1").header("X-User-Id", "user1"))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    // ===================================================================
    // GET /api/stream/notifications (SSE)
    // ===================================================================

    @Nested
    class StreamNotifications {

        @Test
        void missingXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(get(SSE_URL))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void blankXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(get(SSE_URL)
                            .header("X-User-Id", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void invalidFormatXUserIdHeader_returns400() throws Exception {
            mockMvc.perform(get(SSE_URL)
                            .header("X-User-Id", "user:bad"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void mongoDownDuringUnreadFetch_stillReturns200() throws Exception {
            // MongoDB failure for getUnreadCount must NOT prevent SSE connection
            when(notificationService.getUnreadCount("user1"))
                    .thenThrow(new DataAccessException("Mongo down") {});
            when(sseService.connect("user1", 0))
                    .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

            mockMvc.perform(get(SSE_URL)
                            .header("X-User-Id", "user1")
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk());
        }

        @Test
        void redisDownDuringUnreadFetch_sseStillConnectsWithCountZero() throws Exception {
            // Redis DataAccessException is caught in the controller's try-catch;
            // SSE must still connect and the init event must carry unread_count = 0
            when(notificationService.getUnreadCount("user1"))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Redis down"));
            when(sseService.connect("user1", 0))
                    .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

            mockMvc.perform(get(SSE_URL)
                            .header("X-User-Id", "user1")
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk());
        }

        @Test
        void happyPath_returns200() throws Exception {
            when(notificationService.getUnreadCount("user1")).thenReturn(5L);
            when(sseService.connect("user1", 5L))
                    .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

            mockMvc.perform(get(SSE_URL)
                            .header("X-User-Id", "user1")
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk());
        }
    }
}
