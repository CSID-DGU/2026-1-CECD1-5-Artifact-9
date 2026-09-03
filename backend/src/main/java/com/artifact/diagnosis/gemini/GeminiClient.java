package com.artifact.diagnosis.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini generateContent 호출의 공용 껍데기.
 *
 * {@code GeminiService}(처방 코멘트/예비분석 소견)와 {@code CertificateDraftService}(증명서
 * 초안)가 각자 URL 조립·503 재시도·에러 응답 해석을 갖고 있던 걸 여기로 모은다. 프롬프트
 * 조립과 응답 텍스트 파싱은 각 서비스에 그대로 둔다 — 한쪽은 2줄 텍스트, 한쪽은 JSON
 * 스키마로 원하는 응답 형태가 완전히 다르기 때문이다.
 *
 * 절대 예외를 던지지 않는다. 실패하면 {@link GeminiResult#failure}를 돌려주고, 호출부는
 * 그 실패를 안내 문구로 바꿔 사용자에게 보여준다 — AI 호출이 죽어도 진료가 멈추면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final ObjectMapper objectMapper;

    /** 요청마다 새로 만들지 않고 공유한다 — {@code HttpClientConfig} 참고. 연결 타임아웃이 걸려 있다. */
    private final HttpClient httpClient;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.timeout-seconds:15}")
    private long timeoutSeconds;

    @Value("${gemini.model}")
    private String model;

    private static final String GEMINI_URL_FORMAT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private static final int MAX_RETRY = 3;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** responseMimeType/responseSchema 없이 자유 텍스트를 요청한다. */
    public GeminiResult generate(String prompt) {
        return generate(prompt, null, null, null);
    }

    /**
     * @param responseMimeType 구조화된 응답이 필요할 때만 지정한다(예: "application/json"). 그 외엔 null.
     * @param responseSchema   responseMimeType이 JSON일 때 함께 지정하는 스키마. 그 외엔 null.
     * @param temperature      null이면 Gemini 기본값을 쓴다.
     */
    public GeminiResult generate(String prompt, String responseMimeType, Map<String, Object> responseSchema,
                                  Double temperature) {
        if (!hasApiKey()) {
            return GeminiResult.failure("Gemini API 키가 설정되지 않았습니다.");
        }
        // 빈 모델명을 그대로 URL 에 넣으면 /models/:generateContent 로 나가 본문 없는 404 가 돌아온다.
        // 그러면 아래 파싱이 "빈 응답" 으로 오인하므로, 설정 문제는 여기서 그 이름으로 끝낸다.
        if (model == null || model.isBlank()) {
            return GeminiResult.failure("Gemini 모델명이 설정되지 않았습니다. (GEMINI_MODEL)");
        }

        try {
            String body = objectMapper.writeValueAsString(
                    requestBody(prompt, responseMimeType, responseSchema, temperature));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL_FORMAT.formatted(model, apiKey)))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = null;
            // 503(서버 과부하) 한정으로 최대 MAX_RETRY회 재시도
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                log.debug("Gemini 응답 (attempt={}, status={}): {}", attempt, response.statusCode(), response.body());
                if (response.statusCode() != 503) break;
                if (attempt < MAX_RETRY) {
                    log.warn("Gemini 503 과부하, {}초 후 재시도 ({}/{})", attempt, attempt, MAX_RETRY);
                    Thread.sleep(attempt * 1000L); // 1초, 2초 간격
                }
            }

            String rawBody = response.body();
            // 본문이 비어 있는 에러 응답도 있다(모델명이 빠진 URL 의 404 가 그렇다).
            // readTree 에 빈 문자열을 넘기면 버전에 따라 예외이거나 MissingNode 라, 미리 갈라 둔다.
            JsonNode root = rawBody.isBlank() ? objectMapper.missingNode() : objectMapper.readTree(rawBody);

            if (root.has("error")) {
                int code = root.at("/error/code").asInt(0);
                String errorMsg = root.at("/error/message").asText("알 수 없는 오류");
                log.error("Gemini API 에러: {}", errorMsg);
                return GeminiResult.apiError(code, errorMsg);
            }
            if (response.statusCode() / 100 != 2) {
                log.error("Gemini HTTP {} 응답. 본문: {}", response.statusCode(), rawBody);
                return GeminiResult.apiError(response.statusCode(),
                        "AI 서버가 HTTP " + response.statusCode() + " 를 반환했습니다.");
            }

            // parts 는 여러 개로 쪼개져 올 수 있고, 생각(thought) 파트가 섞이기도 한다.
            // parts[0] 만 읽으면 그런 응답이 통째로 빈 응답으로 보인다.
            String text = extractText(root);
            if (text.isEmpty()) {
                String finishReason = root.at("/candidates/0/finishReason").asText("");
                String blockReason = root.at("/promptFeedback/blockReason").asText("");
                log.warn("Gemini 응답 텍스트가 비어있음 (finishReason={}, blockReason={}). 전체 응답: {}",
                        finishReason, blockReason, rawBody);
                return GeminiResult.failure(emptyResponseMessage(finishReason, blockReason));
            }
            return GeminiResult.success(text, model);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Gemini 호출 중단");
            return GeminiResult.failure("AI 호출이 중단되었습니다.");
        } catch (Exception e) {
            log.error("Gemini API 호출 실패: {}", e.getMessage());
            return GeminiResult.failure("AI 호출에 실패했습니다.");
        }
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

    /** 텍스트가 비어 돌아온 이유를 사용자가 다음 행동을 고를 수 있는 문구로 바꾼다. */
    private String emptyResponseMessage(String finishReason, String blockReason) {
        if (!blockReason.isBlank()) {
            return "AI가 안전 정책상 요청을 거부했습니다. (" + blockReason + ")";
        }
        return switch (finishReason) {
            case "SAFETY", "PROHIBITED_CONTENT" -> "AI가 안전 정책상 응답을 거부했습니다.";
            case "RECITATION" -> "AI 응답이 인용 정책에 걸려 중단됐습니다.";
            case "MAX_TOKENS" -> "AI 응답이 길이 제한으로 잘렸습니다. 다시 시도해주세요.";
            default -> "AI가 빈 응답을 반환했습니다. 다시 시도해주세요.";
        };
    }

    private Map<String, Object> requestBody(String prompt, String responseMimeType,
                                             Map<String, Object> responseSchema, Double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (responseMimeType != null) generationConfig.put("responseMimeType", responseMimeType);
        if (responseSchema != null) generationConfig.put("responseSchema", responseSchema);
        if (temperature != null) generationConfig.put("temperature", temperature);
        if (!generationConfig.isEmpty()) body.put("generationConfig", generationConfig);

        return body;
    }
}
