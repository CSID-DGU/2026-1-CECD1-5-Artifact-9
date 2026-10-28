package com.artifact.diagnosis.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.artifact.diagnosis.llm.LlmFailure;
import com.artifact.diagnosis.llm.LlmProvider;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 보조 LLM — Claude(Anthropic Messages API). 공식 Java SDK 로 부른다.
 *
 * Gemini 와 회사가 다르다는 것 자체가 이 클래스의 존재 이유다. Gemini 의 같은 키로 다른 모델을
 * 불러도, Google 쪽 용량이 통째로 모자라거나 프로젝트 쿼터가 바닥나면 함께 실패한다.
 * 그래서 {@code LlmGateway} 체인의 마지막에 둔다 — 여기까지 실패하면 호출부가 안내 문구를 보여 준다.
 *
 * 키({@code ANTHROPIC_API_KEY})가 없으면 체인에서 빠질 뿐 아무 일도 하지 않는다.
 */
@Slf4j
@Component
public class ClaudeClient implements LlmProvider {

    static final String PROVIDER = "claude";

    /** 모델명이 비어 있을 때 쓰는 모델. compose 기본값과 같다. */
    static final String DEFAULT_MODEL = "claude-opus-5-5";

    /**
     * 생각(thinking)이 항상 켜진 모델이 있고, 생각한 토큰도 max_tokens 에 들어간다.
     * 답은 두세 줄(증명서는 한두 문단)이지만 상한을 그 길이에 맞추면 생각만 하다 잘린다.
     * 상한은 쓴 만큼만 과금되므로 넉넉히 둔다. 그래도 잘리면 실패로 본다 — interpret 참고.
     */
    private static final long MAX_TOKENS = 4096L;

    /**
     * 안전 분류기에 걸려 거절된 요청을 서버가 다른 모델로 다시 돌려 주는 기능(fallbacks: "default").
     * 이 기능의 지원 모델 목록에 있는 모델에만 보낸다. Haiku 등은 서버 측 대체 대상이 아니다.
     */
    private static final Set<String> SERVER_FALLBACK_MODELS =
            Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");
    private static final String SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /** 금방 돌아오는 일시 실패. 한 번 더 보내 볼 만하다. 타임아웃은 이미 기다릴 만큼 기다렸으므로 넣지 않는다. */
    private static final Set<LlmFailure> RETRYABLE =
            EnumSet.of(LlmFailure.OVERLOADED, LlmFailure.RATE_LIMITED, LlmFailure.SERVER_ERROR, LlmFailure.NETWORK);

    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxAttempts;
    private final String baseUrl;

    private volatile AnthropicClient client;

    /** 서버 측 대체를 API 가 받지 않으면 끈다. 이후 요청에도 붙이지 않는다 — dropRejectedServerFallback 참고. */
    private volatile boolean serverFallback = true;

    public ClaudeClient(@Value("${anthropic.api.key:}") String apiKey,
                        @Value("${anthropic.model:claude-opus-5-5}") String model,
                        @Value("${anthropic.timeout-seconds:15}") long timeoutSeconds,
                        @Value("${anthropic.max-attempts:2}") int maxAttempts,
                        @Value("${llm.claude-base-url:}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        // 로컬 .env 에 ANTHROPIC_MODEL= 처럼 빈 값을 두면 Spring 기본값이 적용되지 않는다. 그때도 기본 모델을 쓴다.
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    @Override
    public String family() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty() && !model.isEmpty();
    }

    @Override
    public LlmResult generate(LlmRequest request, Duration maxWait) {
        if (!isConfigured()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.NOT_CONFIGURED, 0, "Claude API 키가 설정되지 않았습니다.");
        }

        // 재시도는 SDK 에 맡기지 않고 여기서 한다. SDK 는 타임아웃도 다시 보내서, 받은 시간(maxWait)을
        // 두 배까지 넘길 수 있다. 여기서는 남은 시간 안에서만, 금방 돌아온 실패만 다시 보낸다.
        long deadline = System.nanoTime() + maxWait.toNanos();
        AnthropicClient sdk;
        try {
            // 첫 호출이면 SDK 를 여기서 만든다(1~2초). 그 시간도 받은 시간 안에 넣으려고 시한을 정한 뒤에 만든다.
            sdk = client();
        } catch (RuntimeException e) {
            log.error("Claude 클라이언트 생성 실패: {}", e.getMessage());
            return LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, 0, "AI 클라이언트를 준비하지 못했습니다.");
        }
        LlmResult result = null;
        boolean resent = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration wait = shorter(timeout, remaining(deadline));
            if (wait.isZero()) break;
            result = callOnce(sdk, request, wait);
            if (!resent && dropRejectedServerFallback(result)) {
                // 고친 요청을 바로 다시 보낸다. 실패한 시도가 아니라 요청 모양을 바꾼 것이라 횟수에 넣지 않는다.
                resent = true;
                attempt--;
                continue;
            }
            if (result.success() || !RETRYABLE.contains(result.failure()) || attempt == maxAttempts) break;

            long backoffMs = 1000L * attempt;
            // 쉬고 나서 응답 한 번 받을 시간도 안 남으면 다음 공급자에게 시간을 넘긴다.
            if (remaining(deadline).toMillis() < backoffMs + 2000) break;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LlmResult.failure(PROVIDER, model, LlmFailure.INTERRUPTED, 0, "AI 호출이 중단되었습니다.");
            }
        }
        if (result == null) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.TIMEOUT, 0, "응답 대기 시간이 남지 않아 호출하지 않았습니다.");
        }
        return result;
    }

    private LlmResult callOnce(AnthropicClient sdk, LlmRequest request, Duration wait) {
        try {
            Message message = sdk.messages().create(params(request),
                    RequestOptions.builder().timeout(wait).build());
            return interpret(message);

        // 구체적인 예외부터 잡는다. 재시도해 볼 만한 실패(429·5xx·네트워크)와 설정을 고쳐야 하는
        // 실패(401·403·404)를 체인이 다르게 다루기 때문이다.
        } catch (NotFoundException e) {
            return failure(LlmFailure.MODEL_NOT_FOUND, e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            return failure(LlmFailure.AUTH, e);
        } catch (RateLimitException e) {
            // 사용 등급의 월 지출 상한에 닿은 429 는 retry-after 없이 다음 달까지 계속 실패한다.
            // 잠깐의 요청 한도 초과와 달리 다시 보내도 소용없어 결제 문제로 본다(재시도 대상도 아니다).
            boolean spendCap = "enforced_spend_limit_reached".equals(errorField(e, "/error/details/error_code"));
            return failure(spendCap ? LlmFailure.BILLING : LlmFailure.RATE_LIMITED, e);
        } catch (BadRequestException e) {
            // 크레딧 소진과 직접 정한 지출 한도는 402 가 아니라 400 invalid_request_error 로 오고, 문구로만 구별된다.
            // 요청 모양 탓이 아니라 결제를 고치기 전에는 매번 같은 실패라 결제 문제로 본다.
            return failure(isBillingMessage(errorField(e, "/error/message")) ? LlmFailure.BILLING : LlmFailure.BAD_REQUEST, e);
        } catch (InternalServerException e) {
            boolean overloaded = e.statusCode() == 529
                    || e.errorType().filter(ErrorType.OVERLOADED_ERROR::equals).isPresent();
            return failure(overloaded ? LlmFailure.OVERLOADED : LlmFailure.SERVER_ERROR, e);
        } catch (AnthropicServiceException e) {
            // 402 billing_error(결제 정보 문제)는 SDK 에 전용 예외가 없어 여기로 온다. 결제를 고치기 전에는
            // 계속 실패하므로 설정 문제로 분류한다 — 그래야 체인이 오래 쉬게 하고 Claude 몫을 떼어 두지 않는다.
            if (e.statusCode() == 402 || e.errorType().filter(ErrorType.BILLING_ERROR::equals).isPresent()) {
                return failure(LlmFailure.BILLING, e);
            }
            return failure(e.statusCode() >= 500 ? LlmFailure.SERVER_ERROR : LlmFailure.BAD_REQUEST, e);
        } catch (AnthropicIoException e) {
            return ioFailure(e);
        } catch (AnthropicInvalidDataException e) {
            // 헤더는 받았는데 본문을 읽다 끊기거나 시간이 다 된 경우도 SDK 는 이 예외로 감싼다.
            // 원인이 입출력이면 응답 해석 실패가 아니라 타임아웃·연결 실패다.
            return hasIoCause(e) ? ioFailure(e) : LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, 0,
                    "AI 응답을 해석하지 못했습니다.");
        } catch (RuntimeException e) {
            log.error("Claude 응답 처리 실패 (model={}): {}", model, e.getMessage());
            return LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, 0, "AI 응답을 해석하지 못했습니다.");
        }
    }

    MessageCreateParams params(LlmRequest request) {
        // effort 는 생각의 깊이다. 두세 줄짜리 안내문이라 가장 낮게 둔다 — 지연과 비용이 같이 준다.
        // temperature 는 보내지 않는다. 최신 모델과 현재 API 버전은 sampling 파라미터를 받으면 400 이다.
        OutputConfig.Builder output = OutputConfig.builder().effort(OutputConfig.Effort.LOW);
        if (request.wantsJson()) {
            output.format(JsonOutputFormat.builder().schema(schema(request.jsonFields())).build());
        }
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .outputConfig(output.build())
                .addUserMessage(request.prompt());
        if (sendsServerFallback()) {
            builder.putAdditionalHeader("anthropic-beta", SERVER_FALLBACK_BETA);
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    /** 문자열 필드만 있는 객체. 구조화 출력은 additionalProperties=false 와 required 가 있어야 받는다. */
    private static JsonOutputFormat.Schema schema(List<String> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : fields) {
            properties.put(field, Map.of("type", "string"));
        }
        return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(properties))
                .putAdditionalProperty("required", JsonValue.from(fields))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    private LlmResult interpret(Message message) {
        // 거절·잘림은 HTTP 200 으로 온다. content 를 읽기 전에 stop_reason 부터 본다. 정상 종료(end_turn·
        // stop_sequence)가 아니면 텍스트가 있어도 쓰지 않는다 — 두 줄 코멘트가 첫 줄에서 끊기거나 증명서 JSON 이
        // 닫히지 않은 채 저장되기 때문이다. 실패로 돌려 체인이 다음 공급자로 넘어가게 한다.
        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.BLOCKED, 200, "AI가 안전 정책상 응답을 거부했습니다.");
        }
        if (StopReason.MAX_TOKENS.equals(stop) || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stop)) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.EMPTY, 200, "AI 응답이 길이 제한으로 잘렸습니다.");
        }
        if (stop != null && !StopReason.END_TURN.equals(stop) && !StopReason.STOP_SEQUENCE.equals(stop)) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.BAD_RESPONSE, 200,
                    "AI 응답이 비정상적으로 끝났습니다. (" + stop + ")");
        }
        // 생각(thinking) 블록이 앞에 올 수 있다. 위치가 아니라 종류로 골라 text 블록만 잇는다.
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining())
                .trim();
        if (text.isEmpty()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.EMPTY, 200, "AI가 빈 응답을 반환했습니다.");
        }
        // 서버 측 대체가 일어나면 실제로 답한 모델이 요청한 모델과 다르다. 기록에는 실제 모델을 남긴다.
        return LlmResult.success(text, PROVIDER, message.model().asString());
    }

    private boolean sendsServerFallback() {
        return serverFallback && SERVER_FALLBACK_MODELS.contains(model);
    }

    /**
     * 서버 측 대체는 베타라, 베타가 끝나거나 이 계정에 열려 있지 않으면 요청 전체가 400 이 된다. 그대로 두면 모든
     * Claude 호출이 같은 400 으로 실패하는데, 400 은 체인이 쉬게 하지도 않아 매 요청이 시간만 쓴다.
     * 그 400 이면 이 기능만 끄고 다시 보내라고 알린다. Claude 호출 자체는 계속 쓴다.
     */
    private boolean dropRejectedServerFallback(LlmResult result) {
        if (result.failure() != LlmFailure.BAD_REQUEST || !SERVER_FALLBACK_MODELS.contains(model)) return false;
        String message = result.message() == null ? "" : result.message();
        if (!message.contains("anthropic-beta") && !message.contains("fallbacks")) return false;
        if (serverFallback) {
            serverFallback = false;
            log.warn("Claude 서버 측 대체(fallbacks)를 API 가 받지 않아 빼고 보낸다 (model={}): {}", model, message);
        }
        return true;
    }

    private static boolean isBillingMessage(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("credit balance is too low") || m.contains("you have reached your specified");
    }

    /** 오류 본문의 한 필드. 본문 모양이 예상과 다르면 빈 문자열이다. */
    private static String errorField(AnthropicServiceException e, String pointer) {
        try {
            return e.body().convert(JsonNode.class).at(pointer).asText("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private LlmResult failure(LlmFailure kind, AnthropicServiceException e) {
        return LlmResult.failure(PROVIDER, model, kind, e.statusCode(), e.getMessage());
    }

    private LlmResult ioFailure(Throwable e) {
        boolean timedOut = causeOfType(e, InterruptedIOException.class);
        return LlmResult.failure(PROVIDER, model, timedOut ? LlmFailure.TIMEOUT : LlmFailure.NETWORK, 0,
                timedOut ? "Claude 응답 시간 초과" : "Claude 연결 실패: " + e.getMessage());
    }

    private static boolean hasIoCause(Throwable e) {
        return causeOfType(e, IOException.class);
    }

    private static boolean causeOfType(Throwable e, Class<? extends Throwable> type) {
        for (Throwable c = e.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
            if (type.isInstance(c)) return true;
        }
        return false;
    }

    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    // 재시도는 generate() 가 시간 예산 안에서 직접 한다. builder() 는 fromEnv() 와 달리
                    // 셸 환경변수를 읽지 않는다. 주소는 llm.claude-base-url 로만 바꾼다 — Spring 은 OS 환경변수도
                    // 설정으로 읽으므로, Claude Code 등이 흔히 설정해 두는 ANTHROPIC_BASE_URL 과 겹치는
                    // anthropic.base-url 같은 이름을 쓰면 키와 진료 프롬프트가 그 주소로 나간다.
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .timeout(timeout)
                            .maxRetries(0);
                    if (!baseUrl.isEmpty()) builder.baseUrl(baseUrl);
                    local = builder.build();
                    client = local;
                }
            }
        }
        return local;
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    private static Duration shorter(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    @PreDestroy
    void close() {
        AnthropicClient local = client;
        if (local != null) local.close();
    }
}
