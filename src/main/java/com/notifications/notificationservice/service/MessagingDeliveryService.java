package com.notifications.notificationservice.service;

import com.notifications.notificationservice.client.MessagingServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class MessagingDeliveryService {

    private final MessagingServiceClient client;
    private final String                 secret;

    public MessagingDeliveryService(
            MessagingServiceClient client,
            @Value("${messaging.service.shared-secret}") String secret) {
        this.client = client;
        this.secret = secret;
    }

    public boolean sendEmail(String to, String subject, String body, String correlationId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("channel", "EMAIL");
        request.put("to", to);
        request.put("subject", subject);
        request.put("body", body);
        request.put("html", false);
        request.put("sourceService", "notification-service");
        request.put("correlationId", correlationId);

        try {
            client.sendAsync(secret, request);
            log.info("Email sent to {} (correlationId={})", to, correlationId);
            return true;
        } catch (Exception e) {
            log.error("Failed to send email to {} (correlationId={})", to, correlationId, e);
            return false;
        }
    }

    public boolean sendSms(String phoneNumber, String body, String correlationId) {
        log.info("SMS not yet available — skipping SMS to {} (correlationId={})", phoneNumber, correlationId);
        return false;
    }
}
