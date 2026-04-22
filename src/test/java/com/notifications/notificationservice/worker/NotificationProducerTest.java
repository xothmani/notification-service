package com.notifications.notificationservice.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifications.notificationservice.dto.NotificationPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationProducerTest {

    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock StreamOperations    streamOps;
    @Mock ObjectMapper        objectMapper;

    private NotificationProducer producer;

    private static final String STREAM_KEY = "notifications_stream";

    @BeforeEach
    void setUp() {
        doReturn(streamOps).when(stringRedisTemplate).opsForStream();

        producer = new NotificationProducer(stringRedisTemplate, objectMapper);
        ReflectionTestUtils.setField(producer, "streamKey", STREAM_KEY);
    }

    private NotificationPayload validPayload() {
        return NotificationPayload.builder()
                .recipientIds(List.of("user1"))
                .organizationId("org1")
                .tier("HIGH").type("ALERT")
                .title("T").description("D")
                .redirectUri("https://x.com")
                .channels(List.of("IN_APP"))
                .build();
    }

    // ---------------------------------------------------------------
    // XADD always uses fixed stream key regardless of orgId
    // ---------------------------------------------------------------

    @Test
    void publish_xAddsToFixedStreamKey() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"title\":\"T\"}");

        producer.publish(validPayload());

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());

        assertThat(captor.getValue().getStream()).isEqualTo(STREAM_KEY);
        assertThat(((Map) captor.getValue().getValue())).containsKey("payload");
    }

    @Test
    void publish_xAddsToFixedStreamKey_evenWhenOrganizationIdNull() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        NotificationPayload noOrg = NotificationPayload.builder()
                .recipientIds(List.of("user1")).organizationId(null)
                .tier("HIGH").type("ALERT").title("T").description("D")
                .redirectUri("https://x.com").channels(List.of("IN_APP")).build();

        producer.publish(noOrg);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertThat(captor.getValue().getStream()).isEqualTo(STREAM_KEY);
    }

    @Test
    void publish_trimsStreamAfterAdd() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        producer.publish(validPayload());

        verify(streamOps).trim(eq(STREAM_KEY), eq(10_000L), eq(true));
    }

    // ---------------------------------------------------------------
    // Serialization failure
    // ---------------------------------------------------------------

    @Test
    void publish_serializationFailure_doesNotPublish() throws Exception {
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("serialize error") {});

        producer.publish(validPayload());

        verify(streamOps, never()).add(any());
    }

    // ---------------------------------------------------------------
    // Redis failure on XADD
    // ---------------------------------------------------------------

    @Test
    void publish_redisFailureOnAdd_doesNotPropagate() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(streamOps.add(any())).thenThrow(new RuntimeException("Redis down"));

        assertThatNoException().isThrownBy(() -> producer.publish(validPayload()));
    }
}
