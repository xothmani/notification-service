package com.notifications.notificationservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(name = "messaging-service", url = "${messaging.service.url}")
public interface MessagingServiceClient {

    @PostMapping("/api/messages/send-async")
    void sendAsync(
            @RequestHeader("X-HS-Gateway-Verified") String secret,
            @RequestBody Map<String, Object> request);
}
