package com.artifact.diagnosis.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 생각(thinking)이 항상 켜진 모델이 있고, 생각한 토큰도 max_tokens 에 들어간다.
     * 답은 두세 줄(증명서는 한두 문단)이지만 상한을 그 길이에 맞추면 생각만 하다 잘린다.
     * 상한은 쓴 만큼만 과금되므로 넉넉히 둔다. 그래도 잘리면 실패로 본다 — interpret 참고.
     */
    private static final long MAX_TOKENS = 4096L;

    /**
     * 안전 분류기에 걸려 거절된 요청을 서버가 다른 모델로 다시 돌려 주는 기능(fallbacks: "default").
     * 이 파라미터를 받는 모델에만 보낸다 — 받지 않는 모델(예: Haiku)에 보내면 요청 자체가 400 이 된다.
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

    public ClaudeClient(@Value("${anthropic.api.key:}") String apiKey,
                        @Value("${anthropic.model:claude-opus-5-5}") String model,
                        @Value("${anthropic.timeout-seconds:15}") long timeoutSeconds,
                        @Value("${anthropic.max-attempts:2}") int maxAttempts,
                        @Value("${anthropic.base-url:}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
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
        LlmResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration wait = shorter(timeout, remaining(deadline));
            if (wait.isZero()) break;
            result = callOnce(request, wait);
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

    private LlmResult callOnce(LlmRequest request, Duration wait) {
        try {
            Message message = client().messages().create(params(request),
                    RequestOptions.builder().timeout(wait).build());
            return interpret(message);

        // 구체적인 예외부터 잡는다. 재시도해 볼 만한 실패(429·5xx·네트워크)와 설정을 고쳐야 하는
        // 실패(401·403·404)를 체인이 다르게 다루기 때문이다.
        } catch (NotFoundException e) {
            return failure(LlmFailure.MODEL_NOT_FOUND, e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            return failure(LlmFailure.AUTH, e);
        } catch (RateLimitException e) {
            return failure(LlmFailure.RATE_LIMITED, e);
        } catch (BadRequestException e) {
            return failure(LlmFailure.BAD_REQUEST, e);
        } catch (InternalServerException e) {
            boolean overloaded = e.statusCode() == 529
                    || e.errorType().filter(ErrorType.OVERLOADED_ERROR::equals).isPresent();
            return failure(overloaded ? LlmFailure.OVERLOADED : LlmFailure.SERVER_ERROR, e);
        } catch (AnthropicServiceException e) {
            return failure(e.statusCode() >= 500 ? LlmFailure.SERVER_ERROR : LlmFailure.BAD_REQUEST, e);
        } catch (AnthropicIoException e) {
            boolean timedOut = e.getCause() instanceof InterruptedIOException;
            return LlmResult.failure(PROVIDER, model, timedOut ? LlmFailure.TIMEOUT : LlmFailure.NETWORK, 0,
                    timedOut ? "Claude 응답 시간 초과" : "Claude 연결 실패: " + e.getMessage());
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
        if (SERVER_FALLBACK_MODELS.contains(model)) {
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
        // 거절은 HTTP 200 으로 온다. content 를 읽기 전에 stop_reason 부터 본다.
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.BLOCKED, 200, "AI가 안전 정책상 응답을 거부했습니다.");
        }
        // 길이 제한으로 잘린 답은 쓰지 않는다. 두 줄 코멘트가 첫 줄에서 끊기거나 증명서 JSON 이 닫히지 않은 채
        // 저장되기 때문이다. 실패로 돌려 체인이 다음 공급자로 넘어가게 한다.
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            return LlmResult.failure(PROVIDER, model, LlmFailure.EMPTY, 200, "AI 응답이 길이 제한으로 잘렸습니다.");
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

    private LlmResult failure(LlmFailure kind, AnthropicServiceException e) {
        return LlmResult.failure(PROVIDER, model, kind, e.statusCode(), e.getMessage());
    }

    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    // 재시도는 generate() 가 시간 예산 안에서 직접 한다. builder() 는 fromEnv() 와 달리
                    // ANTHROPIC_BASE_URL 같은 셸 환경변수를 읽지 않는다 — 주소와 키는 Spring 설정으로만 정한다.
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
