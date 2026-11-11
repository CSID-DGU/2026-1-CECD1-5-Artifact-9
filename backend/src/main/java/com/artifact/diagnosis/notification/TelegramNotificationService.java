package com.artifact.diagnosis.notification;

import com.artifact.diagnosis.visit.VisitResponse;
import com.artifact.diagnosis.common.util.DisplayNo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** 팀 부스 시연용 알림. 접수 커밋 후 호출하며 발송 실패는 접수에 영향을 주지 않는다. */
@Slf4j
@Service
public class TelegramNotificationService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String botToken;
    private final String chatId;
    private final String baseUrl;
    // 장애 시 무제한 요청이 쌓이지 않게 한다. 자동 재시도는 중복 발송을 피하기 위해 하지 않는다.
    private final Semaphore inFlight = new Semaphore(10);

    public TelegramNotificationService(HttpClient client, ObjectMapper mapper,
            @Value("${notification.telegram.enabled:false}") boolean enabled,
            @Value("${notification.telegram.bot-token:}") String botToken,
            @Value("${notification.telegram.chat-id:}") String chatId,
            @Value("${notification.telegram.public-base-url:}") String baseUrl) {
        this.client = client;
        this.mapper = mapper;
        this.enabled = enabled;
        this.botToken = botToken.trim();
        this.chatId = chatId.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        if (enabled) {
            URI uri;
            try { uri = URI.create(this.baseUrl); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("TELEGRAM_PUBLIC_BASE_URL 형식을 확인하세요."); }
            if (!this.botToken.matches("[0-9]+:[A-Za-z0-9_-]+") || this.chatId.isBlank()
                    || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException("텔레그램 활성화에는 봇 토큰, 채팅 ID, HTTPS 서비스 origin이 필요합니다.");
            }
        }
    }

    public CompletableFuture<Boolean> notifyReception(VisitResponse visit, String requestedBaseUrl) {
        if (!enabled) return CompletableFuture.completedFuture(false);
        // 화면 QR과 다른 주소를 조용히 보내지 않는다. 목적지는 서버 설정으로 고정한다.
        if (requestedBaseUrl != null && !requestedBaseUrl.isBlank()
                && !baseUrl.equals(requestedBaseUrl.trim().replaceAll("/+$", ""))) {
            log.warn("텔레그램 발송 생략: 화면 QR 주소와 설정 주소가 다릅니다 (visitId={})", visit.id());
            return CompletableFuture.completedFuture(false);
        }
        if (visit.kioskToken() == null || !visit.kioskToken().matches("[A-Za-z0-9]+")) {
            return CompletableFuture.completedFuture(false);
        }
        if (!inFlight.tryAcquire()) {
            log.warn("텔레그램 발송 생략: 동시 요청 한도 (visitId={})", visit.id());
            return CompletableFuture.completedFuture(false);
        }
        try {
            String url = baseUrl + "/kiosk/" + visit.kioskToken();
            var payload = Map.of(
                    "chat_id", chatId,
                    "text", "[Artifact]\n"
                                + "환자 접수가 완료되었습니다.\n"
                                + "환자번호: " + visit.patientId() + "\n"
                                + "접수번호: " + DisplayNo.visit(visit.id()) + "\n"
                                + " ⬇️ 아래 버튼을 눌러 모바일 사전분석을 시작하세요.",
                    "protect_content", true,
                    "link_preview_options", Map.of("is_disabled", true),
                    "reply_markup", Map.of("inline_keyboard", List.of(List.of(
                            Map.of("text", "모바일 사전분석 시작", "url", url)))));
            var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> {
                        try {
                            boolean ok = error == null && response.statusCode() == 200
                                    && mapper.readTree(response.body()).path("ok").asBoolean(false);
                            if (ok) log.info("텔레그램 발송 API 성공 (visitId={})", visit.id());
                            else log.warn("텔레그램 발송 실패 (visitId={})", visit.id());
                            return ok;
                        } catch (Exception e) {
                            log.warn("텔레그램 응답 처리 실패 (visitId={})", visit.id());
                            return false;
                        } finally { inFlight.release(); }
                    });
        } catch (Exception e) {
            inFlight.release();
            // 예외 메시지에는 봇 토큰을 포함한 요청 URL이 들어갈 수 있어 기록하지 않는다.
            log.warn("텔레그램 요청 생성 실패 (visitId={})", visit.id());
            return CompletableFuture.completedFuture(false);
        }
    }
}
