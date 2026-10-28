package com.artifact.diagnosis.gemini;

import com.artifact.diagnosis.llm.FakeLlmServer;
import com.artifact.diagnosis.llm.LlmFailure;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini 응답을 실패 종류로 정확히 가르는지 본다. 체인은 이 분류로 다음 모델로 넘어갈지,
 * 그 모델을 얼마나 쉬게 할지를 정한다 — 분류가 틀리면 대체 경로 전체가 엉뚱하게 돈다.
 * 가짜 서버에 실제 HTTP 로 보내므로 URL·본문 조립까지 운영과 같은 경로를 탄다.
 */
class GeminiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeLlmServer server;
    private GeminiClient client;

    @BeforeEach
    void setUp() {
        server = new FakeLlmServer();
        client = new GeminiClient(objectMapper, HttpClient.newHttpClient());
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(client, "maxAttempts", 1);
        ReflectionTestUtils.setField(client, "baseUrl", server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("호출마다 받은 모델로 URL 을 만들고, 생각(thought) 파트는 답에서 뺀다")
    void callsTheGivenModelAndSkipsThoughtParts() {
        server.on("gemini-backup", 200, FakeLlmServer.geminiText("대체 모델 답"));

        LlmResult result = client.generate("gemini-backup", LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("대체 모델 답");
        assertThat(result.model()).isEqualTo("gemini-backup");
        assertThat(server.received()).singleElement()
                .satisfies(r -> assertThat(r.path()).isEqualTo("/v1beta/models/gemini-backup:generateContent"));
    }

    @Test
    @DisplayName("API 키는 URL 이 아니라 x-goog-api-key 헤더로 보낸다 — URL 은 로그에 남기 쉽다")
    void apiKeyTravelsInHeaderNotUrl() {
        server.on("gemini-a", 200, FakeLlmServer.geminiText("답"));

        client.generate("gemini-a", LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        FakeLlmServer.Received request = server.received().get(0);
        assertThat(request.header("x-goog-api-key")).isEqualTo("test-key");
        assertThat(request.query()).isNull();
    }

    @Test
    @DisplayName("JSON 요청이면 필드 목록을 Gemini 스키마(OBJECT/STRING)로 바꿔 보낸다")
    void jsonRequestBecomesGeminiSchema() throws Exception {
        server.on("gemini-a", 200, FakeLlmServer.geminiText("{\"opinion\":\"소견\"}"));

        client.generate("gemini-a", LlmRequest.json("t", "질문", List.of("opinion", "treatmentPlan"), 0.2),
                Duration.ofSeconds(10));

        JsonNode body = objectMapper.readTree(server.received().get(0).body());
        JsonNode config = body.path("generationConfig");
        assertThat(config.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(config.at("/responseSchema/type").asText()).isEqualTo("OBJECT");
        assertThat(config.at("/responseSchema/properties/treatmentPlan/type").asText()).isEqualTo("STRING");
        assertThat(config.at("/responseSchema/required/1").asText()).isEqualTo("treatmentPlan");
        assertThat(config.path("temperature").asDouble()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("503 은 OVERLOADED — 이번 장애의 원인 응답이다")
    void unavailableIsOverloaded() {
        server.on("gemini-a", 503, FakeLlmServer.geminiError(503, "UNAVAILABLE",
                "The model is overloaded. Please try again later."));

        LlmResult result = client.generate("gemini-a", LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        assertThat(result.failure()).isEqualTo(LlmFailure.OVERLOADED);
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.message()).contains("UNAVAILABLE").contains("overloaded");
        assertThat(result.isTemporaryFailure()).isTrue();
    }

    @Test
    @DisplayName("503 이면 설정한 횟수만큼만 같은 모델에 다시 보낸다")
    void retriesOverloadOnlyUpToMaxAttempts() {
        ReflectionTestUtils.setField(client, "maxAttempts", 2);
        server.on("gemini-a", 503, FakeLlmServer.geminiError(503, "UNAVAILABLE", "overloaded"))
              .on("gemini-a", 200, FakeLlmServer.geminiText("두 번째에 성공"));

        LlmResult result = client.generate("gemini-a", LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        assertThat(result.text()).isEqualTo("두 번째에 성공");
        assertThat(server.received()).hasSize(2);
    }

    @Test
    @DisplayName("429·404·5xx·잘못된 키(400)를 각각 다른 실패로 가른다")
    void classifiesErrorStatuses() {
        server.on("m429", 429, FakeLlmServer.geminiError(429, "RESOURCE_EXHAUSTED", "quota"))
              .on("m404", 404, FakeLlmServer.geminiError(404, "NOT_FOUND", "models/m404 is not found"))
              .on("m500", 500, FakeLlmServer.geminiError(500, "INTERNAL", "internal"))
              .on("mkey", 400, FakeLlmServer.geminiError(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."))
              .on("mbad", 400, FakeLlmServer.geminiError(400, "INVALID_ARGUMENT", "Invalid JSON payload"));

        assertThat(call("m429").failure()).isEqualTo(LlmFailure.RATE_LIMITED);
        assertThat(call("m404").failure()).isEqualTo(LlmFailure.MODEL_NOT_FOUND);
        assertThat(call("m500").failure()).isEqualTo(LlmFailure.SERVER_ERROR);
        assertThat(call("mkey").failure()).isEqualTo(LlmFailure.AUTH);
        assertThat(call("mbad").failure()).isEqualTo(LlmFailure.BAD_REQUEST);
    }

    @Test
    @DisplayName("안전 정책으로 빈 응답이 오면 BLOCKED, 그냥 비었으면 EMPTY")
    void blockedAndEmptyResponses() {
        server.on("blocked", 200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
              .on("empty", 200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}]}");

        assertThat(call("blocked").failure()).isEqualTo(LlmFailure.BLOCKED);
        assertThat(call("empty").failure()).isEqualTo(LlmFailure.EMPTY);
    }

    @Test
    @DisplayName("응답이 늦으면 TIMEOUT — 남은 시간 예산이 설정 타임아웃보다 짧으면 그 예산이 우선한다")
    void slowResponseTimesOutWithinBudget() {
        server.onDelayed("slow", 200, FakeLlmServer.geminiText("늦은 답"), 3000);

        long start = System.nanoTime();
        LlmResult result = client.generate("slow", LlmRequest.text("t", "질문"), Duration.ofMillis(800));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.failure()).isEqualTo(LlmFailure.TIMEOUT);
        assertThat(elapsedMs).isLessThan(2500);
    }

    @Test
    @DisplayName("키가 없거나 모델명이 비면 호출하지 않는다")
    void notConfigured() {
        ReflectionTestUtils.setField(client, "apiKey", "");
        assertThat(call("any").failure()).isEqualTo(LlmFailure.NOT_CONFIGURED);

        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        assertThat(client.generate(" ", LlmRequest.text("t", "질문"), Duration.ofSeconds(5)).failure())
                .isEqualTo(LlmFailure.NOT_CONFIGURED);
        assertThat(server.received()).isEmpty();
    }

    private LlmResult call(String model) {
        return client.generate(model, LlmRequest.text("t", "질문"), Duration.ofSeconds(10));
    }
}
