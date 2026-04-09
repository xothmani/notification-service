package com.notifications.notificationservice.service;

import com.notifications.notificationservice.dto.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SseService {

    // One active emitter per user — new connection replaces the old one
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // 30-minute timeout — client reconnects automatically via EventSource retry
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    public SseEmitter connect(String userId, long unreadCount) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        // Complete the previous connection cleanly before replacing it
        SseEmitter previous = emitters.put(userId, emitter);
        if (previous != null) {
            previous.complete();
        }

        // Use key-value remove so the old emitter's onCompletion callback
        // cannot accidentally evict the new emitter we just registered
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(()    -> emitters.remove(userId, emitter));
        emitter.onError(e       -> emitters.remove(userId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(Map.of("unread_count", unreadCount)));
        } catch (IOException e) {
            log.warn("Failed to send init event to user {}", userId, e);
            emitters.remove(userId, emitter);
        }

        return emitter;
    }

    public void pushNotification(String userId, NotificationResponse notification) {
        send(userId, "new_notification", notification);
    }

    public void pushUnreadCount(String userId, long unreadCount) {
        send(userId, "unread_count_update", Map.of("unread_count", unreadCount));
    }

    private void send(String userId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("SSE send failed for user {} event {} — removing emitter",
                    userId, eventName, e);
            emitters.remove(userId, emitter);
        }
    }
}
