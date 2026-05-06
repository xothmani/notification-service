package com.notifications.notificationservice.service;

import com.notifications.notificationservice.client.MessagingServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessagingDeliveryServiceTest {

    @Mock MessagingServiceClient client;

    private static final String SECRET = "dev-secret";

    private MessagingDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new MessagingDeliveryService(client, SECRET);
    }

    // ---------------------------------------------------------------
    // sendEmail — success
    // ---------------------------------------------------------------

    @Test
    void sendEmail_success_returnsTrue() {
        boolean result = service.sendEmail("user@example.com", "Title", "Body", "corr-123");
        assertThat(result).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendEmail_success_callsClientWithCorrectPayload() {
        service.sendEmail("user@example.com", "Title", "Body", "corr-123");

        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).sendAsync(anyString(), captor.capture());

        Map<Object, Object> req = captor.getValue();
        assertThat(req.get("channel")).isEqualTo("EMAIL");
        assertThat(req.get("to")).isEqualTo("user@example.com");
        assertThat(req.get("subject")).isEqualTo("Title");
        assertThat(req.get("body")).isEqualTo("Body");
        assertThat(req.get("html")).isEqualTo(false);
        assertThat(req.get("sourceService")).isEqualTo("notification-service");
        assertThat(req.get("correlationId")).isEqualTo("corr-123");
    }

    @Test
    void sendEmail_success_usesSharedSecretHeader() {
        service.sendEmail("user@example.com", "Title", "Body", "corr-123");
        verify(client).sendAsync(eq(SECRET), any());
    }

    // ---------------------------------------------------------------
    // sendEmail — messaging service down
    // ---------------------------------------------------------------

    @Test
    void sendEmail_clientThrows_returnsFalse() {
        doThrow(new RuntimeException("messaging service down")).when(client).sendAsync(any(), any());

        boolean result = service.sendEmail("user@example.com", "Title", "Body", "corr-123");

        assertThat(result).isFalse();
    }

    @Test
    void sendEmail_clientThrows_doesNotPropagate() {
        doThrow(new RuntimeException("messaging service down")).when(client).sendAsync(any(), any());

        assertThatNoException().isThrownBy(
                () -> service.sendEmail("user@example.com", "T", "B", "id1"));
    }

    // ---------------------------------------------------------------
    // sendSms — stub (not yet implemented)
    // ---------------------------------------------------------------

    @Test
    void sendSms_returnsAlwaysFalse() {
        boolean result = service.sendSms("+1234567890", "Title: Body", "corr-456");
        assertThat(result).isFalse();
    }

    @Test
    void sendSms_doesNotCallMessagingClient() {
        service.sendSms("+1234567890", "Title: Body", "corr-456");
        verify(client, never()).sendAsync(anyString(), any());
    }
}