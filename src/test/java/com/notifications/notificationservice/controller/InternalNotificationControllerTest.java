package com.notifications.notificationservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.config.SecurityConfig;
import com.notifications.notificationservice.dto.BroadcastStatsResponse;
import com.notifications.notificationservice.dto.NotificationPayload;
import com.notifications.notificationservice.service.NotificationService;
import com.notifications.notificationservice.worker.NotificationProducer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalNotificationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "internal.token=test-internal-token")
class InternalNotificationControllerTest {

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;

    @MockBean NotificationService  notificationService;
    @MockBean NotificationProducer notificationProducer;

    private static final String TOKEN     = "test-internal-token";
    private static final String WRONG_TKN = "wrong-token";
    private static final String STATS_URL = "/api/internal/notifications/broadcast/{id}/stats";
    private static final String BROADCAST_URL = "/api/internal/notifications/broadcast";

    // ===================================================================
    // Security: X-Internal-Token
    // ===================================================================

    @Nested
    class Security {

        @Test
        void missingToken_returns401() throws Exception {
            mockMvc.perform(get(STATS_URL, "b1"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void wrongToken_returns401() throws Exception {
            mockMvc.perform(get(STATS_URL, "b1")
                            .header("X-Internal-Token", WRONG_TKN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void correctToken_passes() throws Exception {
            when(notificationService.getBroadcastStats("b1"))
                    .thenReturn(emptyStats("b1"));

            mockMvc.perform(get(STATS_URL, "b1")
                            .header("X-Internal-Token", TOKEN))
                    .andExpect(status().isOk());
        }
    }

    // ===================================================================
    // POST /api/internal/notifications/broadcast
    // ===================================================================

    @Nested
    class BroadcastEndpoint {

        private final NotificationPayload validPayload = NotificationPayload.builder()
                .recipientIds(List.of("user1"))
                .organizationId("org1")
                .tier("HIGH").type("ALERT")
                .title("Test Title").description("Test Description")
                .redirectUri("https://example.com")
                .channels(List.of("IN_APP"))
                .build();

        @Test
        void happyPath_returns202AndPublishes() throws Exception {
            mockMvc.perform(post(BROADCAST_URL)
                            .header("X-Internal-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validPayload)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));

            verify(notificationProducer).publish(any(NotificationPayload.class));
        }

        @Test
        void missingToken_returns401() throws Exception {
            mockMvc.perform(post(BROADCAST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validPayload)))
                    .andExpect(status().isUnauthorized());

            verify(notificationProducer, never()).publish(any());
        }

        @Test
        void emptyRecipientIds_returns400() throws Exception {
            NotificationPayload bad = NotificationPayload.builder()
                    .recipientIds(List.of()).organizationId("org1")
                    .tier("HIGH").type("ALERT").title("T").description("D")
                    .redirectUri("https://x.com").channels(List.of("IN_APP")).build();

            mockMvc.perform(post(BROADCAST_URL)
                            .header("X-Internal-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest());

            verify(notificationProducer, never()).publish(any());
        }

        @Test
        void missingBody_returns400() throws Exception {
            mockMvc.perform(post(BROADCAST_URL)
                            .header("X-Internal-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===================================================================
    // GET /api/internal/notifications/broadcast/{id}/stats
    // ===================================================================

    @Nested
    class GetBroadcastStats {

        @Test
        void happyPath_returns200WithStats() throws Exception {
            BroadcastStatsResponse stats = BroadcastStatsResponse.builder()
                    .broadcastId("b1").totalSent(10).totalSeen(7).totalClicked(3)
                    .recipients(List.of())
                    .build();
            when(notificationService.getBroadcastStats("b1")).thenReturn(stats);

            mockMvc.perform(get(STATS_URL, "b1")
                            .header("X-Internal-Token", TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.total_sent").value(10))
                    .andExpect(jsonPath("$.data.total_seen").value(7))
                    .andExpect(jsonPath("$.data.total_clicked").value(3));
        }

        @Test
        void mongoDown_returns503() throws Exception {
            when(notificationService.getBroadcastStats(anyString()))
                    .thenThrow(new DataAccessException("Mongo down") {});

            mockMvc.perform(get(STATS_URL, "b1")
                            .header("X-Internal-Token", TOKEN))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        void emptyBroadcastId_returns400() throws Exception {
            mockMvc.perform(get("/api/internal/notifications/broadcast/ /stats")
                            .header("X-Internal-Token", TOKEN))
                    .andExpect(status().isBadRequest());
        }
    }

    private BroadcastStatsResponse emptyStats(String broadcastId) {
        return BroadcastStatsResponse.builder()
                .broadcastId(broadcastId).totalSent(0).totalSeen(0).totalClicked(0)
                .recipients(List.of())
                .build();
    }
}
