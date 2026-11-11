package com.artifact.diagnosis.notification;

import com.artifact.diagnosis.visit.VisitResponse;
import com.artifact.diagnosis.visit.VisitStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramNotificationServiceTest {
    private final HttpClient client = mock(HttpClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final VisitResponse visit = new VisitResponse(12L, 1L, null, VisitStatus.RECEIVED,
            "private memo", "ABC123xyz456", null);
    private TelegramNotificationService service(boolean enabled) {
        return new TelegramNotificationService(client, mapper, enabled, "123:testtoken", "-100123", "https://demo.example");
    }

    @Test void disabledDoesNotSend() {
        assertFalse(service(false).notifyReception(visit, null).join());
        verifyNoInteractions(client);
    }
    @Test void mismatchedQrAddressDoesNotSend() {
        assertFalse(service(true).notifyReception(visit, "https://other.example").join());
        verifyNoInteractions(client);
    }
    @Test void enabledRequiresConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new TelegramNotificationService(
                client, mapper, true, "", "", "http://localhost"));
        assertDoesNotThrow(() -> new TelegramNotificationService(client, mapper, false, "", "", ""));
    }
    @Test void sendsExpectedButtonWithoutPatientData() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertTrue(service(true).notifyReception(visit, "https://demo.example/").join());
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captor.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var body = new java.io.ByteArrayOutputStream();
        captor.getValue().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] bytes = new byte[b.remaining()]; b.get(bytes); body.writeBytes(bytes); }
            public void onError(Throwable t) { fail(t); }
            public void onComplete() { }
        });
        String json = body.toString(StandardCharsets.UTF_8);
        var payload = mapper.readTree(json);
        assertEquals("-100123", payload.path("chat_id").asText());
        assertEquals("https://demo.example/kiosk/ABC123xyz456",
                payload.at("/reply_markup/inline_keyboard/0/0/url").asText());
        assertFalse(json.contains("private memo"));
        assertTrue(payload.at("/link_preview_options/is_disabled").asBoolean());
    }
    @Test void slowAndFailedRequestDoesNotBlockOrThrow() {
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(pending);
        var result = service(true).notifyReception(visit, null);
        assertFalse(result.isDone());
        pending.completeExceptionally(new RuntimeException("network failure"));
        assertFalse(result.join());
    }
    @Test void apiRejectionIsFailureEvenWithHttp200() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":false}");
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertFalse(service(true).notifyReception(visit, null).join());
    }
}
