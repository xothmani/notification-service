package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class NotificationProducer {

    private static final long   STREAM_MAX_LEN = 10_000L;
    private static final String PAYLOAD_FIELD  = "payload";

    @Value("${redis.stream.key}")
    private String streamKey;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper        objectMapper;

    public NotificationProducer(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper        = objectMapper;
    }

    public void publish(NotificationPayload payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for stream {}", streamKey, e);
            return;
        }

        try {
            stringRedisTemplate.opsForStream()
                    .add(MapRecord.create(streamKey, Map.of(PAYLOAD_FIELD, payloadJson)));
            stringRedisTemplate.opsForStream().trim(streamKey, STREAM_MAX_LEN, true);
        } catch (Exception e) {
            log.error("Failed to publish to stream {}", streamKey, e);
            return;
        }

        log.debug("Published notification to stream {}", streamKey);
    }
}
