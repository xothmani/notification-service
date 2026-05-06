package com.notifications.notificationservice.config;

import com.notifications.notificationservice.worker.NotificationWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

@Slf4j
@Configuration
public class RedisStreamConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamContainer(
            RedisConnectionFactory factory,
            StringRedisTemplate stringRedisTemplate,
            @Lazy NotificationWorker notificationWorker,
            @Value("${redis.stream.key}") String streamKey,
            @Value("${redis.stream.consumer-group}") String consumerGroup,
            @Value("${redis.stream.consumer-name}") String consumerName) {

        // MKSTREAM creates the key if it does not exist — fixes the race where
        // the container subscribes before any producer has written to the stream.
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup);
            log.info("Created consumer group '{}' on stream '{}'", consumerGroup, streamKey);
        } catch (Exception e) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (cause != null && cause.contains("BUSYGROUP")) {
                log.info("Consumer group '{}' already exists on stream '{}' — skipping creation",
                        consumerGroup, streamKey);
            } else {
                log.warn("Could not create consumer group '{}' on stream '{}': {}",
                        consumerGroup, streamKey, cause);
            }
        }

        // Uses the auto-configured (pooled) RedisConnectionFactory. The connection pool
        // establishes its connections eagerly at startup when Docker DNS is working, so
        // xReadGroup borrows a pre-resolved pooled connection rather than triggering a
        // new DNS lookup on a Netty I/O thread. pollTimeout of 100ms keeps each
        // XREADGROUP call well under the 2s command timeout on the auto-configured factory.
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(100))
                .serializer(RedisSerializer.string())
                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                (StreamMessageListenerContainer<String, MapRecord<String, String, String>>)
                        StreamMessageListenerContainer.create(factory, options);

        container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                notificationWorker);

        container.start();
        log.info("Stream container started — listening on '{}' as consumer '{}/{}'",
                streamKey, consumerGroup, consumerName);
        return container;
    }
}
