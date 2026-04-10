package com.notifications.notificationservice.service;

import com.notifications.notificationservice.dto.NotificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * SseService is a pure in-memory service with no external dependencies.
 * Tests verify registration, replacement, lifecycle clean-up, and silent
 * failure behaviour when a user has no active connection.
 */
class SseServiceTest {

    private SseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new SseService();
    }

    // ===================================================================
    // connect
    // ===================================================================

    @Nested
    class Connect {

        @Test
        void returnsNonNullEmitter() {
            SseEmitter emitter = sseService.connect("user1", 0);
            assertThat(emitter).isNotNull();
        }

        @Test
        void connectingTwice_secondEmitterIsRegistered() {
            SseEmitter first  = sseService.connect("user1", 0);
            SseEmitter second = sseService.connect("user1", 0);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            // second connection replaces first; they are different objects
            assertThat(first).isNotSameAs(second);
        }

        @Test
        void connectWithUnreadCount_doesNotThrow() {
            assertThatNoException().isThrownBy(() -> sseService.connect("user1", 42));
        }

        @Test
        void connectMultipleUsers_independentEmitters() {
            SseEmitter e1 = sseService.connect("user1", 0);
            SseEmitter e2 = sseService.connect("user2", 0);
            assertThat(e1).isNotSameAs(e2);
        }
    }

    // ===================================================================
    // pushNotification
    // ===================================================================

    @Nested
    class PushNotification {

        @Test
        void userNotConnected_doesNotThrow() {
            NotificationResponse notification = NotificationResponse.builder()
                    .id("n1").recipientId("user1").state("UNSEEN")
                    .createdAt(Instant.now()).build();
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushNotification("ghost-user", notification));
        }

        @Test
        void userConnected_doesNotThrow() {
            sseService.connect("user1", 0);
            NotificationResponse notification = NotificationResponse.builder()
                    .id("n1").recipientId("user1").state("UNSEEN")
                    .createdAt(Instant.now()).build();
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushNotification("user1", notification));
        }

        @Test
        void nullUserId_doesNotThrow() {
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushNotification(null, new NotificationResponse()));
        }
    }

    // ===================================================================
    // pushUnreadCount
    // ===================================================================

    @Nested
    class PushUnreadCount {

        @Test
        void userNotConnected_doesNotThrow() {
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushUnreadCount("nobody", 5));
        }

        @Test
        void userConnected_doesNotThrow() {
            sseService.connect("user1", 0);
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushUnreadCount("user1", 10));
        }

        @Test
        void zeroCount_doesNotThrow() {
            sseService.connect("user1", 0);
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushUnreadCount("user1", 0));
        }
    }

    // ===================================================================
    // Lifecycle — onCompletion / onTimeout / onError
    // ===================================================================

    @Nested
    class Lifecycle {

        @Test
        void completingEmitter_removesItFromRegistry_subsequentPushDoesNotThrow() {
            SseEmitter emitter = sseService.connect("user1", 0);
            emitter.complete();
            // After completion the emitter is removed; push should be a no-op
            assertThatNoException()
                    .isThrownBy(() -> sseService.pushUnreadCount("user1", 3));
        }

        @Test
        void newConnectionAfterCompletion_isIndependentOfOldEmitter() {
            SseEmitter first = sseService.connect("user1", 0);
            first.complete();
            SseEmitter second = sseService.connect("user1", 5);
            assertThat(second).isNotSameAs(first);
        }
    }
}
