package com.notifications.notificationservice.config;

import com.notifications.notificationservice.worker.NotificationWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

@Slf4j
@Configuration
public class RedisStreamConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Dedicated connection factory for the stream listener container.
     * The main factory uses a 2 s command timeout (fast-fail for cache/write ops).
     * XREADGROUP BLOCK blocks for the full poll interval (~2 s) before returning
     * empty — that equals the main timeout and triggers QueryTimeoutException on
     * every idle poll cycle. This factory raises the command timeout to 30 s so
     * the blocking read always completes well within the timeout.
     */
    @Bean
    public RedisConnectionFactory streamConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {

        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(host, port);

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(30))
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @SuppressWarnings("unchecked")
    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamContainer(
            @Qualifier("streamConnectionFactory") RedisConnectionFactory streamFactory,
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

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .serializer(StringRedisSerializer.UTF_8)
                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                (StreamMessageListenerContainer<String, MapRecord<String, String, String>>)
                        StreamMessageListenerContainer.create(streamFactory, options);

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
