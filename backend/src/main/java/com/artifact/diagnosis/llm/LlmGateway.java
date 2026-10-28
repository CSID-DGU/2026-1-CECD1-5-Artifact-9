package com.artifact.diagnosis.llm;

import com.artifact.diagnosis.claude.ClaudeClient;
import com.artifact.diagnosis.gemini.GeminiClient;
import com.artifact.diagnosis.gemini.GeminiModelProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * LLM 호출의 단일 입구. 공급자를 정해진 순서로 시도해 처음 성공한 응답을 돌려준다.
 *
 * <pre>
 *   1. Gemini 1차 모델 (GEMINI_MODEL)
 *   2. Gemini 대체 모델들 (GEMINI_FALLBACK_MODELS) — 같은 키, 다른 모델
 *   3. Claude (ANTHROPIC_API_KEY 가 있을 때만) — 다른 회사
 * </pre>
 *
 * 전부 실패하면 실패 결과를 돌려주고, 호출부가 그것을 안내 문구로 바꾼다(처방 코멘트·증명서 초안은
 * 의사가 직접 쓰고, 키오스크는 "의사 확인 필요" 문구를 보여 준다).
 *
 * 이 체인이 생긴 이유: 처방 코멘트·증명서 초안·키오스크 소견이 모두 Gemini 모델 하나에 묶여 있었다.
 * 그 모델이 503(과부하)을 돌려주면 세 기능이 한꺼번에 "AI 서버가 일시적으로 혼잡합니다"로 멈췄다.
 * 503 은 우리 코드가 아니라 Google 쪽 용량 문제라, 같은 모델에 재시도를 늘려도 풀리지 않는다.
 *
 * <b>쉬게 하기(cooldown).</b> 일시 장애(503·429·5xx·타임아웃)를 낸 공급자는 일정 시간 동안
 * 첫 순서에서 뺀다. 과부하는 대개 몇 분씩 이어지는데, 그동안 매 요청이 같은 모델에서 재시도하며
 * 몇 초씩 버리지 않게 하기 위해서다. 키·모델명 오류(401·403·404)는 설정을 고치기 전까지 계속
 * 실패하므로 더 오래 쉬게 한다. 모든 공급자가 쉬는 중이면 무시하고 순서대로 다시 시도한다.
 *
 * <b>시간 예산.</b> 한 요청이 체인 전체에서 쓸 수 있는 시간을 묶는다. 이 호출은 요청 스레드에서
 * 동기로 돌고 앞단 nginx 는 60초에 끊는다. 공급자마다 타임아웃이 있어도 차례로 다 기다리면 그 합이
 * 길어지므로, 남은 예산이 너무 적으면 다음 공급자를 시작하지 않는다. 또 뒤에 다른 회사 공급자가
 * 기다리고 있으면 그 몫을 남겨 둔다 — 같은 회사 모델들이 응답 없이 시간을 끌어도(타임아웃) 다른
 * 회사 공급자는 한 번은 시도해 보게 하기 위해서다.
 */
@Slf4j
@Service
public class LlmGateway {

    /** 남은 예산이 이보다 적으면 그 공급자를 시작하지 않는다. 두세 줄짜리 답도 이보다 짧은 시간에는 받기 어렵다. */
    private static final Duration MIN_ATTEMPT = Duration.ofSeconds(5);

    /**
     * 뒤에 다른 회사 공급자가 기다리고 있을 때 그 몫으로 떼어 두는 시간. Google 쪽이 통째로 느려지는
     * 장애에서 Gemini 모델들이 예산을 다 써 버려 Claude 가 한 번도 불리지 않는 일을 막는다.
     */
    private static final Duration OTHER_FAMILY_RESERVE = Duration.ofSeconds(12);

    /**
     * 타임아웃을 "그 공급자가 지금 느리다"는 증거로 보려면 적어도 이만큼은 기다려 줬어야 한다.
     * 예산이 모자라 이보다 짧게 끊은 타임아웃은 공급자 탓이 아니므로 쉬게 하지 않는다.
     */
    private static final Duration FAIR_WAIT = Duration.ofSeconds(8);

    /** 키·모델명 오류 뒤 쉬는 시간. 설정을 고치고 재기동하면 표가 비워지므로 길게 잡아도 된다. */
    private static final Duration CONFIG_REST = Duration.ofMinutes(10);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<LlmProvider> providers;
    private final Duration rest;
    private final Duration defaultBudget;
    private final Clock clock;
    /** 경과 시간을 재는 단조 시계. 벽시계(clock)는 NTP 보정으로 뒤로 갈 수 있어 따로 둔다. */
    private final LongSupplier nanoTime;

    /** 공급자 이름 → 다시 첫 순서에 넣을 시각. */
    private final Map<String, Instant> restingUntil = new ConcurrentHashMap<>();

    @Autowired
    public LlmGateway(GeminiClient gemini,
                      ClaudeClient claude,
                      @Value("${gemini.model:}") String primaryModel,
                      @Value("${gemini.fallback-models:}") String fallbackModels,
                      @Value("${llm.cooldown-seconds:60}") long cooldownSeconds,
                      @Value("${llm.total-budget-seconds:30}") long budgetSeconds) {
        this(chain(gemini, claude, primaryModel, fallbackModels),
                Duration.ofSeconds(cooldownSeconds), Duration.ofSeconds(budgetSeconds), Clock.systemUTC());
    }

    /** 공급자 목록을 직접 넘겨 조립한다. 테스트와, 체인을 손으로 구성할 때 쓴다. */
    public LlmGateway(List<LlmProvider> providers, Duration rest, Duration budget, Clock clock) {
        this(providers, rest, budget, clock, System::nanoTime);
    }

    /** 경과 시간까지 테스트가 흘려보낼 때 쓴다. */
    LlmGateway(List<LlmProvider> providers, Duration rest, Duration budget, Clock clock, LongSupplier nanoTime) {
        this.providers = List.copyOf(providers);
        this.rest = rest;
        this.defaultBudget = budget;
        this.clock = clock;
        this.nanoTime = nanoTime;
        log.info("LLM 체인: {}", describe());
    }

    /** 1차 모델 다음에 대체 모델을, 중복 없이 순서대로 붙이고 마지막에 Claude 를 둔다. */
    static List<LlmProvider> chain(GeminiClient gemini, ClaudeClient claude, String primaryModel, String fallbackModels) {
        Set<String> models = new LinkedHashSet<>();
        if (primaryModel != null && !primaryModel.isBlank()) models.add(primaryModel.trim());
        if (fallbackModels != null) {
            Arrays.stream(fallbackModels.split(","))
                    .map(String::trim)
                    .filter(m -> !m.isEmpty())
                    .forEach(models::add);
        }
        List<LlmProvider> chain = new ArrayList<>();
        models.forEach(m -> chain.add(new GeminiModelProvider(gemini, m)));
        chain.add(claude);
        return chain;
    }

    public LlmResult generate(LlmRequest request) {
        return generate(request, defaultBudget);
    }

    /**
     * 시간 예산을 요청마다 더 짧게 잡는다. 같은 요청 안에서 앞 단계가 이미 시간을 쓴 호출부가 쓴다
     * (키오스크는 FastAPI 추론을 기다린 뒤 이 체인을 부른다). 기본 예산보다 길게는 잡을 수 없다.
     */
    public LlmResult generate(LlmRequest request, Duration budget) {
        Duration limit = shorter(budget, defaultBudget);
        List<LlmProvider> usable = providers.stream().filter(LlmProvider::isConfigured).toList();
        if (usable.isEmpty()) {
            return LlmResult.failure("none", null, LlmFailure.NOT_CONFIGURED, 0,
                    "사용할 수 있는 AI 키가 없습니다. (GEMINI_API_KEY / ANTHROPIC_API_KEY)");
        }

        Instant now = clock.instant();
        List<LlmProvider> awake = usable.stream().filter(p -> !isResting(p, now)).toList();
        // 모두 쉬는 중이면 그래도 순서대로 시도한다 — 해 보지도 않고 실패를 돌려주는 것보다 낫다.
        List<LlmProvider> order = awake.isEmpty() ? usable : awake;

        List<String> trail = new ArrayList<>();
        usable.stream().filter(p -> !order.contains(p)).forEach(p -> trail.add(p.name() + "=RESTING"));

        // 다른 회사 몫. 예산이 짧아도 첫 공급자는 MIN_ATTEMPT 를 받도록 그만큼은 덜 떼어 둔다.
        Duration reserve = shorter(OTHER_FAMILY_RESERVE, nonNegative(limit.minus(MIN_ATTEMPT)));

        long startNanos = nanoTime.getAsLong();
        LlmResult firstTemporary = null;
        LlmResult last = null;
        for (int i = 0; i < order.size(); i++) {
            LlmProvider provider = order.get(i);
            Duration left = limit.minusNanos(nanoTime.getAsLong() - startNanos);
            if (left.compareTo(MIN_ATTEMPT) < 0) {
                trail.add(provider.name() + "=SKIPPED(budget)");
                break;
            }
            Duration wait = otherFamilyLater(order, i) ? left.minus(reserve) : left;
            if (wait.compareTo(MIN_ATTEMPT) < 0) {
                // 이 공급자에게 시간을 주면 뒤의 다른 회사 공급자가 시도할 시간이 남지 않는다.
                trail.add(provider.name() + "=SKIPPED(reserve)");
                continue;
            }

            LlmResult result;
            try {
                result = provider.generate(request, wait);
            } catch (RuntimeException e) {
                // 공급자는 예외를 던지지 않기로 약속돼 있다. 그래도 구현 버그 하나가 체인 전체를 끊지 않게 막는다.
                result = LlmResult.failure(provider.family(), provider.model(), LlmFailure.BAD_RESPONSE, 0, e.toString());
            }
            result = checkJson(request, result);
            trail.add(result.summary());

            if (result.success()) {
                restingUntil.remove(provider.name());
                if (trail.size() > 1) {
                    log.info("LLM [{}] 대체 경로로 응답: {}", request.purpose(), String.join(" → ", trail));
                }
                return result.withAttempts(trail);
            }

            log.warn("LLM [{}] {} 실패 {}({}): {}", request.purpose(), provider.name(),
                    result.failure(), result.statusCode(), abbreviate(result.message()));
            restIfNeeded(provider, result, wait);
            if (result.failure().isTemporary() && firstTemporary == null) firstTemporary = result;
            last = result;
        }

        log.error("LLM [{}] 모든 공급자 실패: {}", request.purpose(), String.join(" → ", trail));
        // 한 곳이라도 일시 장애였다면 "잠시 후 다시"가 맞는 안내다. 그래서 그 결과를 대표로 돌려준다.
        LlmResult representative = firstTemporary != null ? firstTemporary : last;
        if (representative == null) {
            representative = LlmResult.failure("none", null, LlmFailure.TIMEOUT, 0, "AI 응답 시간 예산을 모두 썼습니다.");
        }
        return representative.withAttempts(trail);
    }

    /** 기동 로그용. 예: "gemini:gemini-3.1-flash-lite → gemini:gemini-3.5-flash-lite → claude:claude-opus-5-5(키 없음)". */
    public String describe() {
        List<String> names = new ArrayList<>();
        for (LlmProvider p : providers) {
            names.add(p.name() + (p.isConfigured() ? "" : "(키 없음)"));
        }
        return String.join(" → ", names);
    }

    private void restIfNeeded(LlmProvider provider, LlmResult result, Duration wait) {
        LlmFailure failure = result.failure();
        if (failure == LlmFailure.TIMEOUT && wait.compareTo(FAIR_WAIT) < 0) {
            return; // 우리가 짧게 끊은 타임아웃이다. 다음 요청에서 제 시간을 받고 다시 시도한다.
        }
        if (failure.isTemporary()) {
            restingUntil.put(provider.name(), clock.instant().plus(rest));
        } else if (failure.isConfigurationProblem()) {
            restingUntil.put(provider.name(), clock.instant().plus(CONFIG_REST));
        }
    }

    /**
     * JSON 을 요청했는데 형식이 맞지 않으면 성공이 아니라 해석 실패로 바꾼다. 그래야 체인이 다음 공급자로
     * 넘어간다 — 성공으로 돌려주면 호출부가 파싱에 실패한 뒤 다른 공급자를 시도할 방법이 없다.
     * 응답 원문은 메시지에 넣지 않는다. 진료 내용이 담길 수 있어 로그에 남기지 않기 위해서다.
     */
    private static LlmResult checkJson(LlmRequest request, LlmResult result) {
        if (!result.success() || !request.wantsJson()) return result;
        try {
            JsonNode node = JSON.readTree(result.text());
            if (node != null && node.isObject() && request.jsonFields().stream().allMatch(node::has)) {
                return result;
            }
        } catch (JsonProcessingException e) {
            // 아래에서 실패로 바꾼다.
        }
        return LlmResult.failure(result.provider(), result.model(), LlmFailure.BAD_RESPONSE, result.statusCode(),
                "요청한 JSON 형식이 아닌 응답 (" + result.text().length() + "자)");
    }

    private static boolean otherFamilyLater(List<LlmProvider> order, int index) {
        String family = order.get(index).family();
        for (int j = index + 1; j < order.size(); j++) {
            if (!order.get(j).family().equals(family)) return true;
        }
        return false;
    }

    private boolean isResting(LlmProvider provider, Instant now) {
        Instant until = restingUntil.get(provider.name());
        return until != null && now.isBefore(until);
    }

    private static Duration shorter(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }

    private static String abbreviate(String message) {
        if (message == null) return "";
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
