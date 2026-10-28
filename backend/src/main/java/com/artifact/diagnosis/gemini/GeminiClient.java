package com.artifact.diagnosis.gemini;

import com.artifact.diagnosis.llm.LlmFailure;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gemini generateContent 호출의 공용 껍데기.
 *
 * 어떤 모델을 부를지는 호출마다 받는다. 1차 모델이 과부하(503)로 거절하면 {@code LlmGateway} 가
 * 같은 키로 다른 Gemini 모델을 부르기 때문이다 — 모델 하나에 묶인 클라이언트로는 그게 안 된다.
 * 모델 하나를 공급자 하나로 감싸는 일은 {@link GeminiModelProvider} 가 한다.
 *
 * 절대 예외를 던지지 않는다. 실패하면 {@link LlmResult#failure} 를 돌려주고, 호출부는
 * 그 실패를 다음 공급자로 넘기거나 안내 문구로 바꾼다 — AI 호출이 죽어도 진료가 멈추면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    static final String PROVIDER = "gemini";

    /** 답이 끝까지 왔다는 뜻의 finishReason. 비어 있으면(필드 없음) 끝난 것으로 본다. */
    private static final Set<String> COMPLETE = Set.of("", "STOP");
    /** 길이 제한으로 잘렸다. 텍스트가 있어도 반쪽이다. */
    private static final Set<String> TRUNCATED = Set.of("MAX_TOKENS", "CONTINUATION");
    /** 정책으로 막혔다. 다른 공급자는 답할 수 있다. */
    private static final Set<String> POLICY = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "ESCALATION", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT");
    /** 계정이 이용 정책 위반으로 제한됐다. 키를 바꾸기 전에는 계속 실패한다. */
    private static final String ACCOUNT_LIMITED = "PUP_LIMITED_DISABLED";

    private final ObjectMapper objectMapper;

    /** 요청마다 새로 만들지 않고 공유한다 — {@code HttpClientConfig} 참고. 연결 타임아웃이 걸려 있다. */
    private final HttpClient httpClient;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.timeout-seconds:15}")
    private long timeoutSeconds;

    /**
     * 같은 모델에 503 이 오면 몇 번까지 다시 보낼지(첫 시도 포함).
     * 예전에는 3번이었다. 지금은 다음 모델이 뒤에 대기하고 있어서, 같은 모델에 오래 매달릴수록
     * 사용자가 기다리는 시간만 늘어난다. 기본 2번(1초 쉬고 한 번 더)으로 줄였다.
     */
    @Value("${gemini.max-attempts:2}")
    private int maxAttempts;

    /** 운영은 기본값 그대로다. 통합 테스트가 가짜 서버를 가리키게 할 때만 바꾼다. */
    @Value("${gemini.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /**
     * 키는 URL(?key=)이 아니라 x-goog-api-key 헤더로 보낸다. URL 은 예외 메시지·프록시 로그에
     * 그대로 남기 쉬워, 키가 따라 새어 나갈 수 있기 때문이다.
     */
    private static final String GEMINI_PATH_FORMAT = "/v1beta/models/%s:generateContent";

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param model   부를 모델 ID. 비어 있으면 호출하지 않고 실패로 돌려준다.
     * @param maxWait 이 호출(재시도 포함)에 쓸 수 있는 시간. 응답 타임아웃은 이 값과 설정값 중 짧은 쪽이다.
     */
    public LlmResult generate(String model, LlmRequest request, Duration maxWait) {
        if (!hasApiKey()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.NOT_CONFIGURED, 0, "Gemini API 키가 설정되지 않았습니다.");
        }
        // 빈 모델명을 그대로 URL 에 넣으면 /models/:generateContent 로 나가 본문 없는 404 가 돌아온다.
        // 그러면 아래 파싱이 "빈 응답" 으로 오인하므로, 설정 문제는 여기서 그 이름으로 끝낸다.
        if (model == null || model.isBlank()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.NOT_CONFIGURED, 0,
                    "Gemini 모델명이 설정되지 않았습니다. (GEMINI_MODEL)");
        }

        long deadline = System.nanoTime() + maxWait.toNanos();
        try {
            String body = objectMapper.writeValueAsString(requestBody(request));

            HttpResponse<String> response = null;
            for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
                Duration timeout = shorter(Duration.ofSeconds(timeoutSeconds), remaining(deadline));
                if (timeout.isZero()) break;
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + GEMINI_PATH_FORMAT.formatted(model)))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                response = exchange(httpRequest, timeout);
                log.debug("Gemini 응답 (model={}, attempt={}, status={}): {}",
                        model, attempt, response.statusCode(), response.body());
                if (response.statusCode() != 503 || attempt >= maxAttempts) break;
                // 503 은 Google 쪽 용량 부족이라 잠깐 뒤에는 받아 주기도 한다. 쉬고 나서 응답 한 번 받을
                // 시간도 안 남으면 여기서 멈추고, 남은 시간은 체인의 다음 모델에게 넘긴다.
                long backoffMs = 1000L * attempt;
                if (remaining(deadline).toMillis() < backoffMs + 2000) break;
                Thread.sleep(backoffMs);
            }
            if (response == null) {
                return LlmResult.failure(PROVIDER, model, LlmFailure.TIMEOUT, 0, "응답 대기 시간이 남지 않아 호출하지 않았습니다.");
            }
            return interpret(model, response);

        } catch (HttpTimeoutException | TimeoutException e) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.TIMEOUT, 0, "Gemini 응답 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmResult.failure(PROVIDER, model, LlmFailure.INTERRUPTED, 0, "AI 호출이 중단되었습니다.");
        } catch (IOException e) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.NETWORK, 0, "Gemini 연결 실패: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Gemini 응답 처리 실패 (model={}): {}", model, e.getMessage());
            return LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, 0, "AI 응답을 해석하지 못했습니다.");
        }
    }

    /**
     * 요청 한 번을 시한 안에 끝낸다. {@link HttpRequest.Builder#timeout} 은 응답 헤더가 올 때까지만 잰다 —
     * 헤더 뒤에 본문이 멈추면 끝없이 기다리고, 체인의 시간 예산과 Claude 몫까지 먹어 버린다.
     * 그래서 본문까지 다 받는 교환 전체에 같은 시한을 걸고, 넘기면 요청을 취소한다.
     */
    private HttpResponse<String> exchange(HttpRequest request, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io; // HttpTimeoutException 도 여기로 온다.
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        }
    }

    private LlmResult interpret(String model, HttpResponse<String> response) {
        int status = response.statusCode();
        String rawBody = response.body();
        // 본문이 비어 있는 에러 응답도 있다(모델명이 빠진 URL 의 404 가 그렇다).
        // readTree 에 빈 문자열을 넘기면 버전에 따라 예외이거나 MissingNode 라, 미리 갈라 둔다.
        JsonNode root;
        try {
            root = rawBody == null || rawBody.isBlank() ? objectMapper.missingNode() : objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            // Google 앞단·프록시가 주는 HTML 오류 페이지(502·503·429 등). 연결 실패가 아니라 상태코드로 가른다.
            if (status / 100 != 2) {
                return LlmResult.failure(PROVIDER, model, classify(status, ""), status,
                        "AI 서버가 HTTP " + status + " 를 반환했습니다 (JSON 이 아닌 본문).");
            }
            return LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, status, "AI 응답을 해석하지 못했습니다.");
        }

        if (root.has("error")) {
            int code = root.at("/error/code").asInt(status);
            String errorStatus = root.at("/error/status").asText("");
            String errorMsg = root.at("/error/message").asText("알 수 없는 오류");
            return LlmResult.failure(PROVIDER, model, classify(code, errorMsg), code,
                    (errorStatus.isBlank() ? "" : errorStatus + ": ") + errorMsg);
        }
        if (status / 100 != 2) {
            return LlmResult.failure(PROVIDER, model, classify(status, ""), status,
                    "AI 서버가 HTTP " + status + " 를 반환했습니다.");
        }

        String blockReason = root.at("/promptFeedback/blockReason").asText("");
        if (!blockReason.isBlank()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.BLOCKED, status,
                    "AI가 안전 정책상 요청을 거부했습니다. (" + blockReason + ")");
        }

        // 끝난 이유부터 본다. 텍스트가 있어도 잘렸거나 정책으로 멈춘 답은 쓰지 않는다 — 반쪽 코멘트가
        // 처방 기록에 저장되거나, 다른 공급자가 답할 수 있는데 체인이 거기서 멈추기 때문이다.
        String finishReason = root.at("/candidates/0/finishReason").asText("");
        if (!COMPLETE.contains(finishReason)) {
            log.warn("Gemini 응답이 정상 종료가 아님 (model={}, finishReason={})", model, finishReason);
            if (TRUNCATED.contains(finishReason)) {
                return LlmResult.failure(PROVIDER, model, LlmFailure.EMPTY, status, "AI 응답이 길이 제한으로 잘렸습니다.");
            }
            if (POLICY.contains(finishReason)) {
                return LlmResult.failure(PROVIDER, model, LlmFailure.BLOCKED, status,
                        "AI가 정책상 응답을 멈췄습니다. (" + finishReason + ")");
            }
            if (ACCOUNT_LIMITED.equals(finishReason)) {
                return LlmResult.failure(PROVIDER, model, LlmFailure.AUTH, status,
                        "Gemini 계정이 이용 제한 상태입니다. (" + finishReason + ")");
            }
            return LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, status,
                    "AI 응답이 비정상적으로 끝났습니다. (" + finishReason + ")");
        }

        // parts 는 여러 개로 쪼개져 올 수 있고, 생각(thought) 파트가 섞이기도 한다.
        // parts[0] 만 읽으면 그런 응답이 통째로 빈 응답으로 보인다.
        String text = extractText(root);
        if (text.isEmpty()) {
            log.warn("Gemini 응답 텍스트가 비어있음 (model={}, finishReason={})", model, finishReason);
            return LlmResult.failure(PROVIDER, model, LlmFailure.EMPTY, status, "AI가 빈 응답을 반환했습니다.");
        }
        // 별칭(gemini-flash-lite-latest 등)으로 불렀으면 실제로 답한 모델은 modelVersion 에 있다.
        // 처방·증명서 기록에 남는 값이라 별칭보다 실제 모델이 낫다.
        String answered = root.path("modelVersion").asText("");
        return LlmResult.success(text, PROVIDER, answered.isBlank() ? model : answered);
    }

    /**
     * 상태코드를 실패 종류로 바꾼다. Gemini 는 잘못된 API 키도 401 이 아니라 400 INVALID_ARGUMENT 로
     * 돌려준다("API key not valid"). 이걸 BAD_REQUEST 로 두면 키가 틀린 채로 매 요청 같은 실패를 반복하므로
     * 메시지로 갈라 AUTH 로 분류한다.
     */
    static LlmFailure classify(int code, String message) {
        if (code == 503) return LlmFailure.OVERLOADED;
        if (code == 429) return LlmFailure.RATE_LIMITED;
        if (code == 401 || code == 403) return LlmFailure.AUTH;
        if (code == 404) return LlmFailure.MODEL_NOT_FOUND;
        if (code == 400 && message != null && message.toLowerCase().contains("api key")) return LlmFailure.AUTH;
        if (code >= 500) return LlmFailure.SERVER_ERROR;
        return LlmFailure.BAD_REQUEST;
    }

    /** 첫 후보의 모든 파트에서 본문 텍스트를 이어 붙인다. 생각(thought) 파트는 답이 아니므로 건너뛴다. */
    private String extractText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : root.at("/candidates/0/content/parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            sb.append(part.path("text").asText(""));
        }
        return sb.toString().trim();
    }

    private Map<String, Object> requestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", request.prompt())))));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (request.wantsJson()) {
            // 응답 형식을 스키마로 못 박는다. Gemini 스키마는 OpenAPI 부분집합이라 타입 이름이 대문자다.
            Map<String, Object> properties = new LinkedHashMap<>();
            for (String field : request.jsonFields()) {
                properties.put(field, Map.of("type", "STRING"));
            }
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", Map.of(
                    "type", "OBJECT",
                    "properties", properties,
                    "required", request.jsonFields()));
        }
        if (request.temperature() != null) generationConfig.put("temperature", request.temperature());
        if (!generationConfig.isEmpty()) body.put("generationConfig", generationConfig);

        return body;
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    private static Duration shorter(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
