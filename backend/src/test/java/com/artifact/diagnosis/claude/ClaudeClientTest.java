package com.artifact.diagnosis.claude;

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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude 호출이 요청을 올바른 모양으로 보내고, 응답과 오류를 체인이 쓰는 실패 종류로 정확히 가르는지 본다.
 * 공식 SDK 가 가짜 서버에 실제 HTTP 로 보내므로, SDK 의 직렬화·예외 매핑까지 운영과 같은 경로를 탄다.
 */
class ClaudeClientTest {

    private static final String MESSAGES = "/v1/messages";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeLlmServer server;

    @BeforeEach
    void setUp() {
        server = new FakeLlmServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("생각(thinking) 블록은 건너뛰고 text 블록만 답으로 쓰며, 실제로 답한 모델을 남긴다")
    void returnsTextBlocksAndTheModelThatAnswered() {
        // 서버 측 대체가 일어나면 요청한 모델과 다른 모델이 답한다. 기록에는 실제 모델이 남아야 한다.
        server.on(MESSAGES, 200, FakeLlmServer.claudeText("claude-opus-5", "대체 응답입니다."));

        LlmResult result = client("claude-opus-5-5", 2).generate(LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("대체 응답입니다.");
        assertThat(result.provider()).isEqualTo("claude");
        assertThat(result.model()).isEqualTo("claude-opus-5");
        // 셸 환경변수가 아니라 설정으로 넣은 키가 나가는지 본다. 어느 헤더에 싣는지는 SDK 가 정한다
        // (이 버전은 Authorization: Bearer).
        assertThat(server.received()).singleElement().satisfies(r -> assertThat(
                r.header("x-api-key") != null ? r.header("x-api-key") : r.header("authorization"))
                .contains("test-key"));
    }

    @Test
    @DisplayName("JSON 요청은 output_config.format 스키마로 보내고, temperature 는 보내지 않는다")
    void jsonRequestUsesStructuredOutputWithoutTemperature() throws Exception {
        server.on(MESSAGES, 200, FakeLlmServer.claudeText("claude-opus-5-5", "{\"opinion\":\"소견\"}"));

        client("claude-opus-5-5", 1).generate(
                LlmRequest.json("t", "질문", List.of("opinion", "treatmentPlan"), 0.2), Duration.ofSeconds(10));

        JsonNode body = objectMapper.readTree(server.received().get(0).body());
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
        // 최신 모델은 temperature 를 받으면 400 이다. 증명서 요청의 0.2 는 Gemini 에만 간다.
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.at("/output_config/effort").asText()).isEqualTo("low");
        JsonNode schema = body.at("/output_config/format/schema");
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.at("/properties/treatmentPlan/type").asText()).isEqualTo("string");
        assertThat(schema.at("/required/1").asText()).isEqualTo("treatmentPlan");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("서버 측 대체(fallbacks)는 그 기능을 받는 모델에만 보낸다 — 다른 모델에 보내면 요청이 400 이 된다")
    void serverSideFallbackOnlyForSupportedModels() throws Exception {
        server.on(MESSAGES, 200, FakeLlmServer.claudeText("claude-opus-5-5", "답"));
        client("claude-opus-5-5", 1).generate(LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        FakeLlmServer.Received opus = server.received().get(0);
        assertThat(objectMapper.readTree(opus.body()).path("fallbacks").asText()).isEqualTo("default");
        assertThat(opus.header("anthropic-beta")).contains("server-side-fallback-2026-07-01");

        server.reset();
        server.on(MESSAGES, 200, FakeLlmServer.claudeText("claude-haiku-5-5", "답"));
        client("claude-haiku-5-5", 1).generate(LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        FakeLlmServer.Received haiku = server.received().get(0);
        assertThat(objectMapper.readTree(haiku.body()).has("fallbacks")).isFalse();
        assertThat(haiku.header("anthropic-beta")).isNull();
    }

    @Test
    @DisplayName("거절은 HTTP 200 으로 온다 — stop_reason 으로 BLOCKED 를 가른다")
    void refusalIsBlocked() {
        server.on(MESSAGES, 200, FakeLlmServer.claudeRefusal("claude-opus-5-5"));

        LlmResult result = client("claude-opus-5-5", 2).generate(LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        assertThat(result.failure()).isEqualTo(LlmFailure.BLOCKED);
        assertThat(result.statusCode()).isEqualTo(200);
        // 거절은 다시 보내도 같다. 재시도하지 않는다.
        assertThat(server.received()).hasSize(1);
    }

    @Test
    @DisplayName("529 는 OVERLOADED, 429·404·401·500 은 각각 다른 실패로 가른다")
    void classifiesErrorStatuses() {
        server.on(MESSAGES, 529, FakeLlmServer.claudeError("overloaded_error", "Overloaded"))
              .on(MESSAGES, 429, FakeLlmServer.claudeError("rate_limit_error", "Too many requests"))
              .on(MESSAGES, 404, FakeLlmServer.claudeError("not_found_error", "model: claude-nope"))
              .on(MESSAGES, 401, FakeLlmServer.claudeError("authentication_error", "invalid x-api-key"))
              .on(MESSAGES, 500, FakeLlmServer.claudeError("api_error", "Internal server error"));
        ClaudeClient client = client("claude-opus-5-5", 1);

        LlmResult overloaded = call(client);
        assertThat(overloaded.failure()).isEqualTo(LlmFailure.OVERLOADED);
        assertThat(overloaded.statusCode()).isEqualTo(529);
        assertThat(call(client).failure()).isEqualTo(LlmFailure.RATE_LIMITED);
        assertThat(call(client).failure()).isEqualTo(LlmFailure.MODEL_NOT_FOUND);
        assertThat(call(client).failure()).isEqualTo(LlmFailure.AUTH);
        assertThat(call(client).failure()).isEqualTo(LlmFailure.SERVER_ERROR);
        // SDK 가 몰래 다시 보내지 않는다 — 재시도 횟수와 시간은 우리 코드가 쥔다.
        assertThat(server.received()).hasSize(5);
    }

    @Test
    @DisplayName("금방 돌아온 과부하(529)는 남은 시간 안에서 한 번 더 보낸다")
    void retriesFastOverloadWithinBudget() {
        server.on(MESSAGES, 529, FakeLlmServer.claudeError("overloaded_error", "Overloaded"))
              .on(MESSAGES, 200, FakeLlmServer.claudeText("claude-opus-5-5", "두 번째에 성공"));

        LlmResult result = client("claude-opus-5-5", 2).generate(LlmRequest.text("t", "질문"), Duration.ofSeconds(10));

        assertThat(result.text()).isEqualTo("두 번째에 성공");
        assertThat(server.received()).hasSize(2);
    }

    @Test
    @DisplayName("쉬고 다시 보낼 시간이 남지 않았으면 재시도하지 않는다")
    void doesNotRetryWithoutTimeLeft() {
        server.on(MESSAGES, 529, FakeLlmServer.claudeError("overloaded_error", "Overloaded"))
              .on(MESSAGES, 200, FakeLlmServer.claudeText("claude-opus-5-5", "늦은 성공"));

        LlmResult result = client("claude-opus-5-5", 2).generate(LlmRequest.text("t", "질문"), Duration.ofMillis(2500));

        assertThat(result.failure()).isEqualTo(LlmFailure.OVERLOADED);
        assertThat(server.received()).hasSize(1);
    }

    @Test
    @DisplayName("응답이 늦으면 받은 시간 안에서 TIMEOUT 으로 끊고, 타임아웃은 다시 보내지 않는다")
    void slowResponseTimesOutWithoutRetry() {
        server.onDelayed(MESSAGES, 200, FakeLlmServer.claudeText("claude-opus-5-5", "늦은 답"), 3000);

        long start = System.nanoTime();
        LlmResult result = client("claude-opus-5-5", 2).generate(LlmRequest.text("t", "질문"), Duration.ofMillis(800));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.failure()).isEqualTo(LlmFailure.TIMEOUT);
        assertThat(elapsedMs).isLessThan(2500);
        assertThat(server.received()).hasSize(1);
    }

    @Test
    @DisplayName("키가 없으면 체인에서 빠지고, 불려도 호출하지 않는다")
    void notConfiguredWithoutKey() {
        ClaudeClient client = new ClaudeClient("", "claude-opus-5-5", 5, 2, server.baseUrl());

        assertThat(client.isConfigured()).isFalse();
        assertThat(call(client).failure()).isEqualTo(LlmFailure.NOT_CONFIGURED);
        assertThat(server.received()).isEmpty();
    }

    private ClaudeClient client(String model, int maxAttempts) {
        return new ClaudeClient("test-key", model, 5, maxAttempts, server.baseUrl());
    }

    private static LlmResult call(ClaudeClient client) {
        return client.generate(LlmRequest.text("t", "질문"), Duration.ofSeconds(10));
    }
}
