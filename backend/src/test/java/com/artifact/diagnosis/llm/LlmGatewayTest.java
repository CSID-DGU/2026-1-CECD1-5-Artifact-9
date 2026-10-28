package com.artifact.diagnosis.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대체 체인의 규칙을 고정한다: 순서대로 시도하고, 일시 장애를 낸 공급자는 쉬게 하고,
 * 설정 오류는 더 오래 쉬게 하고, 시간 예산을 넘기지 않고, 같은 회사 모델들이 시간을 끌어도
 * 다른 회사 공급자의 몫은 남기고, 그쪽마저 실패하면 미뤄 둔 공급자가 남은 시간을 받고,
 * 구현 버그 하나가 체인을 끊지 않는다.
 *
 * 시간은 테스트가 직접 흘려보낸다({@link Ticker}). 공급자가 "15초 동안 응답이 없다"를
 * 실제로 기다리지 않고 재현하기 위해서다.
 */
class LlmGatewayTest {

    private static final LlmRequest REQUEST = LlmRequest.text("test", "프롬프트");
    private static final LlmRequest JSON_REQUEST =
            LlmRequest.json("test", "프롬프트", List.of("opinion", "treatmentPlan"), 0.2);
    private static final Duration REST = Duration.ofSeconds(60);
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final Duration KIOSK = Duration.ofSeconds(20);
    /** 운영 기본값과 같은 공급자 자체 타임아웃. */
    private static final Duration OWN_TIMEOUT = Duration.ofSeconds(15);

    @Test
    @DisplayName("1차 공급자가 성공하면 그 응답을 쓰고 뒤는 부르지 않는다")
    void primarySuccessStopsTheChain() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t).then(ok("첫 응답"));
        FakeProvider backup = claude("b", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, primary, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("첫 응답");
        assertThat(backup.calls).isZero();
    }

    @Test
    @DisplayName("1차가 과부하(503)면 다음 공급자의 응답을 돌려주고, 거친 경로를 남긴다")
    void overloadFallsBackToNextProvider() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t).then(fail(LlmFailure.OVERLOADED, 503));
        FakeProvider backup = gemini("b", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, primary, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("b");
        assertThat(result.attempts()).containsExactly("gemini:a=OVERLOADED(503)", "gemini:b=OK");
    }

    @Test
    @DisplayName("과부하를 낸 공급자는 쉬는 동안 건너뛰고, 시간이 지나면 다시 1순위로 돌아온다")
    void overloadedProviderRestsThenReturns() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t)
                .then(fail(LlmFailure.OVERLOADED, 503))
                .then(ok("회복"));
        FakeProvider backup = gemini("b", t).then(ok("대체 응답"));
        LlmGateway gateway = gateway(t, primary, backup);

        gateway.generate(REQUEST);
        LlmResult second = gateway.generate(REQUEST);

        // 쉬는 중이라 1차는 아예 부르지 않는다 — 매 요청 재시도로 몇 초씩 버리지 않는다.
        assertThat(primary.calls).isEqualTo(1);
        assertThat(second.model()).isEqualTo("b");
        assertThat(second.attempts()).first().asString().isEqualTo("gemini:a=RESTING");

        t.advance(Duration.ofSeconds(61));
        LlmResult third = gateway.generate(REQUEST);

        assertThat(primary.calls).isEqualTo(2);
        assertThat(third.text()).isEqualTo("회복");
    }

    @Test
    @DisplayName("모두 쉬는 중이면 실패를 바로 돌려주지 않고 순서대로 다시 시도한다")
    void allRestingStillTries() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t)
                .then(fail(LlmFailure.OVERLOADED, 503))
                .then(ok("재시도 성공"));
        LlmGateway gateway = gateway(t, primary);

        gateway.generate(REQUEST);
        LlmResult second = gateway.generate(REQUEST);

        assertThat(second.success()).isTrue();
        assertThat(primary.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("안전 정책 거절은 쉬게 하지 않는다 — 다음 요청은 다시 1차부터 간다")
    void blockedDoesNotRest() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t)
                .then(fail(LlmFailure.BLOCKED, 200))
                .then(ok("다른 질문에는 답함"));
        FakeProvider backup = claude("b", t).then(ok("대체 응답"));
        LlmGateway gateway = gateway(t, primary, backup);

        LlmResult first = gateway.generate(REQUEST);
        LlmResult second = gateway.generate(REQUEST);

        assertThat(first.model()).isEqualTo("b");
        assertThat(second.text()).isEqualTo("다른 질문에는 답함");
    }

    @Test
    @DisplayName("키·모델명 오류는 10분 쉬게 한다 — 설정을 고치기 전에는 계속 실패하기 때문이다")
    void configurationProblemRestsLonger() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t)
                .then(fail(LlmFailure.MODEL_NOT_FOUND, 404))
                .then(ok("설정 복구"));
        FakeProvider backup = gemini("b", t).then(ok("대체 응답"));
        LlmGateway gateway = gateway(t, primary, backup);

        gateway.generate(REQUEST);
        t.advance(Duration.ofMinutes(5));
        gateway.generate(REQUEST);
        assertThat(primary.calls).isEqualTo(1);

        t.advance(Duration.ofMinutes(6));
        LlmResult later = gateway.generate(REQUEST);
        assertThat(primary.calls).isEqualTo(2);
        assertThat(later.text()).isEqualTo("설정 복구");
    }

    @Test
    @DisplayName("동시에 들어온 일시 장애가 설정 오류의 10분 쉬기를 60초로 줄이지 못한다")
    void temporaryFailureCannotShortenConfigurationRest() {
        Ticker t = new Ticker();
        FakeProvider p = gemini("a", t)
                .then(fail(LlmFailure.AUTH, 403))
                .then(fail(LlmFailure.OVERLOADED, 503))
                .then(ok("너무 일찍 돌아옴"));
        FakeProvider q = gemini("b", t)
                .then(fail(LlmFailure.OVERLOADED, 503))
                .then(ok("대체 응답"));
        LlmGateway gateway = gateway(t, p, q);

        gateway.generate(REQUEST);   // p 는 10분, q 는 60초 쉰다.
        gateway.generate(REQUEST);   // 모두 쉬는 중이라 다시 시도 — p 가 이번엔 503 을 낸다.
        t.advance(Duration.ofSeconds(61));
        LlmResult later = gateway.generate(REQUEST);

        assertThat(later.attempts()).containsExactly("gemini:a=RESTING", "gemini:b=OK");
        assertThat(p.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("늦게 끝난 성공이, 그사이 다른 요청이 새로 걸어 둔 쉬기를 지우지 못한다")
    void lateSuccessDoesNotClearANewerRest() {
        Ticker t = new Ticker();
        AtomicReference<LlmGateway> gatewayRef = new AtomicReference<>();
        // 요청 A 가 p 를 기다리는 동안 요청 B 가 들어와 p 에서 503 을 받고 p 를 쉬게 한다. 그 뒤 A 의 p 가 성공한다.
        FakeProvider p = gemini("a", t)
                .thenRunning(() -> gatewayRef.get().generate(REQUEST), ok("늦게 끝난 성공"))
                .then(fail(LlmFailure.OVERLOADED, 503))
                .then(ok("다시 불림"));
        FakeProvider q = gemini("b", t).then(ok("대체 응답"));
        gatewayRef.set(gateway(t, p, q));

        LlmResult a = gatewayRef.get().generate(REQUEST);
        LlmResult c = gatewayRef.get().generate(REQUEST);

        assertThat(a.text()).isEqualTo("늦게 끝난 성공");
        assertThat(c.attempts()).containsExactly("gemini:a=RESTING", "gemini:b=OK");
    }

    @Test
    @DisplayName("모두 실패하면, 한 곳이라도 일시 장애였을 때 '잠시 후 다시' 안내가 가능한 실패를 돌려준다")
    void allFailedPrefersTemporaryFailure() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t).then(fail(LlmFailure.OVERLOADED, 503));
        FakeProvider backup = claude("b", t).then(fail(LlmFailure.BLOCKED, 200));

        LlmResult result = gateway(t, primary, backup).generate(REQUEST);

        assertThat(result.success()).isFalse();
        assertThat(result.isTemporaryFailure()).isTrue();
        assertThat(result.attempts()).containsExactly("gemini:a=OVERLOADED(503)", "claude:b=BLOCKED(200)");
    }

    @Test
    @DisplayName("키가 하나도 없으면 아무것도 부르지 않고 NOT_CONFIGURED 를 돌려준다")
    void nothingConfigured() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t).unconfigured();

        LlmResult result = gateway(t, primary).generate(REQUEST);

        assertThat(result.failure()).isEqualTo(LlmFailure.NOT_CONFIGURED);
        assertThat(primary.calls).isZero();
    }

    @Test
    @DisplayName("키가 없는 공급자만 빠지고 나머지는 그대로 돈다")
    void unconfiguredProviderIsSkipped() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t).unconfigured();
        FakeProvider backup = claude("b", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, primary, backup).generate(REQUEST);

        assertThat(result.model()).isEqualTo("b");
        assertThat(primary.calls).isZero();
    }

    @Test
    @DisplayName("앞 공급자가 쓴 시간만큼 줄어든 예산을 다음 공급자에게 넘긴다")
    void remainingBudgetIsPassedDown() {
        Ticker t = new Ticker();
        // 7초 끌다가 503 — 느린 과부하 응답이다.
        FakeProvider primary = gemini("a", t).thenAfter(Duration.ofSeconds(7), fail(LlmFailure.OVERLOADED, 503));
        FakeProvider backup = gemini("b", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, primary, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(primary.lastMaxWait).isEqualTo(BUDGET);
        assertThat(backup.lastMaxWait).isEqualTo(Duration.ofSeconds(23));
    }

    @Test
    @DisplayName("남은 예산이 응답 한 번 받기에도 모자라면 다음 공급자를 시작하지 않는다")
    void startsNothingWithoutEnoughBudget() {
        Ticker t = new Ticker();
        // 둘 다 13초씩 끌다가 503 — 30초 중 4초가 남는다.
        FakeProvider g1 = gemini("a", t).thenAfter(Duration.ofSeconds(13), fail(LlmFailure.OVERLOADED, 503));
        FakeProvider g2 = gemini("b", t).thenAfter(Duration.ofSeconds(13), fail(LlmFailure.OVERLOADED, 503));
        FakeProvider g3 = gemini("c", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, g1, g2, g3).generate(REQUEST);

        assertThat(result.success()).isFalse();
        assertThat(g3.calls).isZero();
        assertThat(result.attempts()).containsExactly(
                "gemini:a=OVERLOADED(503)", "gemini:b=OVERLOADED(503)", "gemini:c=SKIPPED(budget)");
    }

    @Test
    @DisplayName("Gemini 모델들이 응답 없이 시간을 끌어도 Claude 몫은 남는다 — 예산을 다 써 버려 Claude 를 못 부르는 일이 없다")
    void hangingGeminiCannotStarveClaude() {
        Ticker t = new Ticker();
        FakeProvider g1 = gemini("a", t).thenHang();
        FakeProvider g2 = gemini("b", t).thenHang();
        FakeProvider g3 = gemini("c", t).thenHang();
        FakeProvider fallback = claude("d", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, g1, g2, g3, fallback).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("d");
        // 1차는 30초 중 Claude 몫 12초를 뺀 18초를 받지만 자기 타임아웃(15초)에서 끊긴다.
        assertThat(g1.lastMaxWait).isEqualTo(Duration.ofSeconds(18));
        // 남은 15초에서 12초를 떼면 3초 — 시작하지 않고 미뤄 둔다.
        assertThat(g2.calls).isZero();
        assertThat(g3.calls).isZero();
        assertThat(fallback.lastMaxWait).isEqualTo(Duration.ofSeconds(15));
        assertThat(result.attempts()).containsExactly(
                "gemini:a=TIMEOUT", "gemini:b=SKIPPED(reserve)", "gemini:c=SKIPPED(reserve)", "claude:d=OK");
    }

    @Test
    @DisplayName("Claude 몫 때문에 미뤘던 모델은, Claude 가 금방 실패하면 남은 시간을 받는다")
    void deferredProvidersGetTheRestWhenClaudeFailsFast() {
        Ticker t = new Ticker();
        FakeProvider g1 = gemini("a", t).thenHang();
        FakeProvider g2 = gemini("b", t).then(ok("미뤄 뒀던 모델의 응답"));
        FakeProvider fallback = claude("c", t).then(fail(LlmFailure.BAD_REQUEST, 400));

        LlmResult result = gateway(t, g1, g2, fallback).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("b");
        assertThat(g2.lastMaxWait).isEqualTo(Duration.ofSeconds(15));
        assertThat(result.attempts()).containsExactly(
                "gemini:a=TIMEOUT", "gemini:b=SKIPPED(reserve)", "claude:c=BAD_REQUEST(400)", "gemini:b=OK");
    }

    @Test
    @DisplayName("키오스크(20초): 1차가 끌면 Claude 가 12초를 받고, Claude 가 결제 오류면 미뤘던 모델이 남은 시간을 받는다")
    void kioskBudgetReachesClaudeThenDeferredModel() {
        Ticker t = new Ticker();
        FakeProvider g1 = gemini("a", t).thenHang();
        FakeProvider g2 = gemini("b", t).then(ok("대체 응답"));
        FakeProvider fallback = claude("c", t).then(fail(LlmFailure.BILLING, 402));
        LlmGateway gateway = gateway(t, g1, g2, fallback);

        LlmResult first = gateway.generate(REQUEST, KIOSK);

        assertThat(g1.lastMaxWait).isEqualTo(Duration.ofSeconds(8));
        assertThat(fallback.lastMaxWait).isEqualTo(Duration.ofSeconds(12));
        assertThat(g2.lastMaxWait).isEqualTo(Duration.ofSeconds(12));
        assertThat(first.attempts()).containsExactly(
                "gemini:a=TIMEOUT", "gemini:b=SKIPPED(reserve)", "claude:c=BILLING(402)", "gemini:b=OK");

        // 결제 오류는 설정 문제라 Claude 가 오래 쉰다. 1차도 8초를 받고 응답이 없었으니 쉰다.
        // 그러면 몫을 떼어 둘 이유가 없어 2번째 모델이 예산 전부를 받는다.
        LlmResult second = gateway.generate(REQUEST, KIOSK);
        assertThat(second.attempts()).containsExactly("gemini:a=RESTING", "claude:c=RESTING", "gemini:b=OK");
        assertThat(g2.lastMaxWait).isEqualTo(KIOSK);
    }

    @Test
    @DisplayName("키오스크 1차 타임아웃은 나노초 오차가 있어도 똑같이 쉬게 한다 — 경계값에 걸리지 않는다")
    void kioskTimeoutRestsDespiteClockJitter() {
        Ticker t = new Ticker().withJitter(Duration.ofNanos(1_000));
        FakeProvider g1 = gemini("a", t).thenHang().then(ok("회복"));
        FakeProvider fallback = claude("c", t).then(ok("Claude 응답"));
        LlmGateway gateway = gateway(t, g1, fallback);

        gateway.generate(REQUEST, KIOSK);
        LlmResult second = gateway.generate(REQUEST, KIOSK);

        assertThat(second.attempts()).first().asString().isEqualTo("gemini:a=RESTING");
    }

    @Test
    @DisplayName("Claude 가 없으면 몫을 떼어 두지 않는다 — Gemini 모델들이 예산을 모두 쓴다")
    void noReserveWithoutAnotherFamily() {
        Ticker t = new Ticker();
        FakeProvider g1 = gemini("a", t).then(ok("응답"));
        FakeProvider g2 = gemini("b", t).then(ok("응답"));

        gateway(t, g1, g2).generate(REQUEST);

        assertThat(g1.lastMaxWait).isEqualTo(BUDGET);
    }

    @Test
    @DisplayName("예산이 모자라 짧게 끊은 타임아웃은 쉬게 하지 않는다 — 공급자 탓이 아니다")
    void shortTimeoutDoesNotRest() {
        Ticker t = new Ticker();
        FakeProvider g1 = gemini("a", t).thenHang();
        // 2번째 모델은 정상이지만 6초가 걸린다. 1차가 15초를 쓴 뒤라 5초밖에 받지 못한다.
        FakeProvider g2 = gemini("b", t).thenAfter(Duration.ofSeconds(6), ok("느린 정상 응답"));
        LlmGateway gateway = gateway(t, g1, g2);

        LlmResult first = gateway.generate(REQUEST, KIOSK);
        assertThat(first.success()).isFalse();
        assertThat(first.attempts()).containsExactly("gemini:a=TIMEOUT", "gemini:b=TIMEOUT");

        LlmResult second = gateway.generate(REQUEST, KIOSK);

        // 1차는 제 시간(15초)을 받고도 응답이 없었으니 쉬고, 2번째는 5초에 끊겼을 뿐이니 다시 시도한다.
        assertThat(second.success()).isTrue();
        assertThat(second.attempts()).containsExactly("gemini:a=RESTING", "gemini:b=OK");
        assertThat(g2.lastMaxWait).isEqualTo(KIOSK);
    }

    @Test
    @DisplayName("요청별 예산은 기본 예산보다 길게 잡을 수 없다")
    void perRequestBudgetIsCappedByDefault() {
        Ticker t = new Ticker();
        FakeProvider primary = gemini("a", t).then(ok("응답"));
        LlmGateway gateway = gateway(t, primary);

        gateway.generate(REQUEST, KIOSK);
        assertThat(primary.lastMaxWait).isEqualTo(KIOSK);

        gateway.generate(REQUEST, Duration.ofSeconds(40));
        assertThat(primary.lastMaxWait).isEqualTo(BUDGET);
    }

    @Test
    @DisplayName("JSON 을 요청했는데 형식이 아니거나 필드가 빠지면 실패로 보고 다음 공급자로 넘어간다")
    void invalidJsonMovesOnToNextProvider() {
        Ticker t = new Ticker();
        FakeProvider prose = gemini("a", t).then(ok("소견: 아토피피부염입니다."));
        FakeProvider partial = gemini("b", t).then(ok("{\"opinion\":\"소견\"}"));
        FakeProvider valid = claude("c", t).then(ok("{\"opinion\":\"소견\",\"treatmentPlan\":\"계획\"}"));
        LlmGateway gateway = gateway(t, prose, partial, valid);

        LlmResult result = gateway.generate(JSON_REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("c");
        assertThat(result.attempts()).containsExactly(
                "gemini:a=BAD_RESPONSE(200)", "gemini:b=BAD_RESPONSE(200)", "claude:c=OK");

        // 형식이 틀린 것은 장애가 아니다. 쉬게 하지 않으므로 다음 요청도 1차부터 간다.
        LlmResult next = gateway.generate(REQUEST);
        assertThat(next.model()).isEqualTo("a");
        assertThat(prose.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("공급자 구현이 예외를 던져도 체인은 다음으로 넘어간다")
    void providerExceptionDoesNotBreakTheChain() {
        Ticker t = new Ticker();
        FakeProvider broken = gemini("a", t).throwing();
        FakeProvider backup = claude("b", t).then(ok("대체 응답"));

        LlmResult result = gateway(t, broken, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("b");
        assertThat(result.attempts()).first().asString().isEqualTo("gemini:a=BAD_RESPONSE");
    }

    @Test
    @DisplayName("운영과 같은 실제 시계로도 예산을 넘겨준다")
    void realClockPassesBudgetDown() {
        FakeProvider primary = gemini("a", new Ticker()).then(ok("응답"));

        new LlmGateway(List.of(primary), REST, BUDGET).generate(REQUEST);

        assertThat(primary.lastMaxWait).isLessThanOrEqualTo(BUDGET).isGreaterThan(Duration.ofSeconds(25));
    }

    /* ---------------------------------------------------------------- */

    private static LlmGateway gateway(Ticker t, LlmProvider... providers) {
        return new LlmGateway(List.of(providers), REST, BUDGET, t::read);
    }

    private static FakeProvider gemini(String model, Ticker t) {
        return new FakeProvider("gemini", model, t);
    }

    private static FakeProvider claude(String model, Ticker t) {
        return new FakeProvider("claude", model, t);
    }

    /** 공급자·모델은 FakeProvider 가 채운다. */
    private static LlmResult ok(String text) {
        return LlmResult.success(text, null, null);
    }

    private static LlmResult fail(LlmFailure failure, int status) {
        return LlmResult.failure(null, null, failure, status, failure.name());
    }

    /**
     * 정해 둔 결과를 순서대로 돌려주는 공급자. 마지막 결과는 계속 반복한다.
     * 결과마다 "그만큼 시간을 쓰고 나서" 돌려줄 수 있다(시계를 그만큼 흘려보낸다).
     */
    private static final class FakeProvider implements LlmProvider {
        private record Step(Duration spend, boolean hang, Runnable before, LlmResult result) {}

        private final String family;
        private final String model;
        private final Ticker ticker;
        private final Deque<Step> steps = new ArrayDeque<>();
        private boolean configured = true;
        private boolean throwing = false;
        int calls = 0;
        Duration lastMaxWait;

        FakeProvider(String family, String model, Ticker ticker) {
            this.family = family;
            this.model = model;
            this.ticker = ticker;
        }

        FakeProvider then(LlmResult result) {
            return thenAfter(Duration.ZERO, result);
        }

        FakeProvider thenAfter(Duration spend, LlmResult result) {
            steps.add(new Step(spend, false, null, result));
            return this;
        }

        /** 결과를 돌려주기 전에 다른 일을 한다 — 그사이 다른 요청이 끼어드는 상황을 만든다. */
        FakeProvider thenRunning(Runnable before, LlmResult result) {
            steps.add(new Step(Duration.ZERO, false, before, result));
            return this;
        }

        /** 응답이 없다 — 받은 시간과 자기 타임아웃 중 짧은 쪽을 다 쓰고 TIMEOUT. */
        FakeProvider thenHang() {
            steps.add(new Step(Duration.ZERO, true, null, null));
            return this;
        }

        FakeProvider unconfigured() {
            configured = false;
            return this;
        }

        FakeProvider throwing() {
            throwing = true;
            return this;
        }

        @Override
        public String family() {
            return family;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public LlmResult generate(LlmRequest request, Duration maxWait) {
            calls++;
            lastMaxWait = maxWait;
            if (throwing) throw new IllegalStateException("구현 버그");
            Step step = steps.size() > 1 ? steps.poll() : steps.peek();
            Duration ownLimit = maxWait.compareTo(OWN_TIMEOUT) < 0 ? maxWait : OWN_TIMEOUT;
            if (step.hang() || step.spend().compareTo(ownLimit) > 0) {
                // 받은 시간(또는 자기 타임아웃) 안에 끝나지 않는 응답이다. 그 시간을 다 쓰고 끊긴다.
                ticker.advance(ownLimit);
                return LlmResult.failure(family, model, LlmFailure.TIMEOUT, 0, "응답 시간 초과");
            }
            if (step.before() != null) step.before().run();
            ticker.advance(step.spend());
            LlmResult r = step.result();
            return new LlmResult(r.success(), r.text(), family, model, r.failure(), r.statusCode(), r.message(), List.of());
        }
    }

    /** 테스트가 흘려보내는 단조 시계(나노초). jitter 를 주면 읽을 때마다 그만큼 저절로 흐른다. */
    private static final class Ticker {
        private long nanos = 0;
        private long jitterNanos = 0;

        Ticker withJitter(Duration perRead) {
            jitterNanos = perRead.toNanos();
            return this;
        }

        void advance(Duration d) {
            nanos += d.toNanos();
        }

        long read() {
            nanos += jitterNanos;
            return nanos;
        }
    }
}
