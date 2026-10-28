package com.artifact.diagnosis.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대체 체인의 규칙을 고정한다: 순서대로 시도하고, 일시 장애를 낸 공급자는 쉬게 하고,
 * 설정 오류는 더 오래 쉬게 하고, 시간 예산을 넘기지 않고, 구현 버그 하나가 체인을 끊지 않는다.
 */
class LlmGatewayTest {

    private static final LlmRequest REQUEST = LlmRequest.text("test", "프롬프트");

    @Test
    @DisplayName("1차 공급자가 성공하면 그 응답을 쓰고 뒤는 부르지 않는다")
    void primarySuccessStopsTheChain() {
        FakeProvider primary = new FakeProvider("gemini:a").then(ok("gemini:a", "첫 응답"));
        FakeProvider backup = new FakeProvider("claude:b").then(ok("claude:b", "대체 응답"));

        LlmResult result = gateway(new MutableClock(), primary, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("첫 응답");
        assertThat(backup.calls).isZero();
    }

    @Test
    @DisplayName("1차가 과부하(503)면 다음 공급자의 응답을 돌려주고, 거친 경로를 남긴다")
    void overloadFallsBackToNextProvider() {
        FakeProvider primary = new FakeProvider("gemini:a").then(fail("gemini:a", LlmFailure.OVERLOADED, 503));
        FakeProvider backup = new FakeProvider("gemini:b").then(ok("gemini:b", "대체 응답"));

        LlmResult result = gateway(new MutableClock(), primary, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("gemini:b");
        assertThat(result.attempts()).containsExactly("gemini:gemini:a=OVERLOADED(503)", "gemini:gemini:b=OK");
    }

    @Test
    @DisplayName("과부하를 낸 공급자는 쉬는 동안 건너뛰고, 시간이 지나면 다시 1순위로 돌아온다")
    void overloadedProviderRestsThenReturns() {
        MutableClock clock = new MutableClock();
        FakeProvider primary = new FakeProvider("gemini:a")
                .then(fail("gemini:a", LlmFailure.OVERLOADED, 503))
                .then(ok("gemini:a", "회복"));
        FakeProvider backup = new FakeProvider("gemini:b").then(ok("gemini:b", "대체 응답"));
        LlmGateway gateway = gateway(clock, primary, backup);

        gateway.generate(REQUEST);
        LlmResult second = gateway.generate(REQUEST);

        // 쉬는 중이라 1차는 아예 부르지 않는다 — 매 요청 재시도로 몇 초씩 버리지 않는다.
        assertThat(primary.calls).isEqualTo(1);
        assertThat(second.model()).isEqualTo("gemini:b");
        assertThat(second.attempts()).first().asString().endsWith("=RESTING");

        clock.advance(Duration.ofSeconds(61));
        LlmResult third = gateway.generate(REQUEST);

        assertThat(primary.calls).isEqualTo(2);
        assertThat(third.text()).isEqualTo("회복");
    }

    @Test
    @DisplayName("모두 쉬는 중이면 실패를 바로 돌려주지 않고 순서대로 다시 시도한다")
    void allRestingStillTries() {
        MutableClock clock = new MutableClock();
        FakeProvider primary = new FakeProvider("gemini:a")
                .then(fail("gemini:a", LlmFailure.OVERLOADED, 503))
                .then(ok("gemini:a", "재시도 성공"));
        LlmGateway gateway = gateway(clock, primary);

        gateway.generate(REQUEST);
        LlmResult second = gateway.generate(REQUEST);

        assertThat(second.success()).isTrue();
        assertThat(primary.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("안전 정책 거절은 쉬게 하지 않는다 — 다음 요청은 다시 1차부터 간다")
    void blockedDoesNotRest() {
        FakeProvider primary = new FakeProvider("gemini:a")
                .then(fail("gemini:a", LlmFailure.BLOCKED, 200))
                .then(ok("gemini:a", "다른 질문에는 답함"));
        FakeProvider backup = new FakeProvider("claude:b").then(ok("claude:b", "대체 응답"));
        LlmGateway gateway = gateway(new MutableClock(), primary, backup);

        LlmResult first = gateway.generate(REQUEST);
        LlmResult second = gateway.generate(REQUEST);

        assertThat(first.model()).isEqualTo("claude:b");
        assertThat(second.text()).isEqualTo("다른 질문에는 답함");
    }

    @Test
    @DisplayName("키·모델명 오류는 10분 쉬게 한다 — 설정을 고치기 전에는 계속 실패하기 때문이다")
    void configurationProblemRestsLonger() {
        MutableClock clock = new MutableClock();
        FakeProvider primary = new FakeProvider("gemini:a")
                .then(fail("gemini:a", LlmFailure.MODEL_NOT_FOUND, 404))
                .then(ok("gemini:a", "설정 복구"));
        FakeProvider backup = new FakeProvider("gemini:b").then(ok("gemini:b", "대체 응답"));
        LlmGateway gateway = gateway(clock, primary, backup);

        gateway.generate(REQUEST);
        clock.advance(Duration.ofMinutes(5));
        gateway.generate(REQUEST);
        assertThat(primary.calls).isEqualTo(1);

        clock.advance(Duration.ofMinutes(6));
        LlmResult later = gateway.generate(REQUEST);
        assertThat(primary.calls).isEqualTo(2);
        assertThat(later.text()).isEqualTo("설정 복구");
    }

    @Test
    @DisplayName("모두 실패하면, 한 곳이라도 일시 장애였을 때 '잠시 후 다시' 안내가 가능한 실패를 돌려준다")
    void allFailedPrefersTemporaryFailure() {
        FakeProvider primary = new FakeProvider("gemini:a").then(fail("gemini:a", LlmFailure.OVERLOADED, 503));
        FakeProvider backup = new FakeProvider("claude:b").then(fail("claude:b", LlmFailure.BLOCKED, 200));

        LlmResult result = gateway(new MutableClock(), primary, backup).generate(REQUEST);

        assertThat(result.success()).isFalse();
        assertThat(result.isTemporaryFailure()).isTrue();
        assertThat(result.attempts()).hasSize(2);
    }

    @Test
    @DisplayName("키가 하나도 없으면 아무것도 부르지 않고 NOT_CONFIGURED 를 돌려준다")
    void nothingConfigured() {
        FakeProvider primary = new FakeProvider("gemini:a").unconfigured();

        LlmResult result = gateway(new MutableClock(), primary).generate(REQUEST);

        assertThat(result.failure()).isEqualTo(LlmFailure.NOT_CONFIGURED);
        assertThat(primary.calls).isZero();
    }

    @Test
    @DisplayName("키가 없는 공급자만 빠지고 나머지는 그대로 돈다")
    void unconfiguredProviderIsSkipped() {
        FakeProvider primary = new FakeProvider("gemini:a").unconfigured();
        FakeProvider backup = new FakeProvider("claude:b").then(ok("claude:b", "대체 응답"));

        LlmResult result = gateway(new MutableClock(), primary, backup).generate(REQUEST);

        assertThat(result.model()).isEqualTo("claude:b");
        assertThat(primary.calls).isZero();
    }

    @Test
    @DisplayName("공급자에게 남은 시간 예산을 넘겨주고, 예산이 모자라면 다음 공급자를 시작하지 않는다")
    void budgetIsPassedDownAndEnforced() {
        FakeProvider primary = new FakeProvider("gemini:a").then(ok("gemini:a", "응답"));
        LlmResult ok = new LlmGateway(List.of(primary), Duration.ofSeconds(60), Duration.ofSeconds(30),
                new MutableClock()).generate(REQUEST);
        assertThat(ok.success()).isTrue();
        assertThat(primary.lastMaxWait).isLessThanOrEqualTo(Duration.ofSeconds(30)).isGreaterThan(Duration.ofSeconds(25));

        FakeProvider starved = new FakeProvider("gemini:c").then(ok("gemini:c", "응답"));
        LlmResult skipped = new LlmGateway(List.of(starved), Duration.ofSeconds(60), Duration.ofSeconds(1),
                new MutableClock()).generate(REQUEST);
        assertThat(skipped.success()).isFalse();
        assertThat(starved.calls).isZero();
        assertThat(skipped.attempts()).containsExactly("gemini:c=SKIPPED(budget)");
    }

    @Test
    @DisplayName("호출부가 준 시간 예산이 기본 예산보다 우선한다 — 키오스크처럼 앞 단계가 이미 시간을 쓴 경우")
    void perRequestBudgetOverridesDefault() {
        FakeProvider primary = new FakeProvider("gemini:a").then(ok("gemini:a", "응답"));

        new LlmGateway(List.of(primary), Duration.ofSeconds(60), Duration.ofSeconds(30), new MutableClock())
                .generate(REQUEST, Duration.ofSeconds(20));

        assertThat(primary.lastMaxWait).isLessThanOrEqualTo(Duration.ofSeconds(20)).isGreaterThan(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("공급자 구현이 예외를 던져도 체인은 다음으로 넘어간다")
    void providerExceptionDoesNotBreakTheChain() {
        FakeProvider broken = new FakeProvider("gemini:a").throwing();
        FakeProvider backup = new FakeProvider("claude:b").then(ok("claude:b", "대체 응답"));

        LlmResult result = gateway(new MutableClock(), broken, backup).generate(REQUEST);

        assertThat(result.success()).isTrue();
        assertThat(result.model()).isEqualTo("claude:b");
    }

    /* ---------------------------------------------------------------- */

    private static LlmGateway gateway(Clock clock, LlmProvider... providers) {
        return new LlmGateway(List.of(providers), Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    }

    private static LlmResult ok(String model, String text) {
        return LlmResult.success(text, "gemini", model);
    }

    private static LlmResult fail(String model, LlmFailure failure, int status) {
        return LlmResult.failure("gemini", model, failure, status, failure.name());
    }

    /** 정해 둔 결과를 순서대로 돌려주는 공급자. 마지막 결과는 계속 반복한다. */
    private static final class FakeProvider implements LlmProvider {
        private final String name;
        private final Deque<LlmResult> results = new ArrayDeque<>();
        private boolean configured = true;
        private boolean throwing = false;
        int calls = 0;
        Duration lastMaxWait;

        FakeProvider(String name) {
            this.name = name;
        }

        FakeProvider then(LlmResult result) {
            results.add(result);
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
        public String name() {
            return name;
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
            return results.size() > 1 ? results.poll() : results.peek();
        }
    }

    /** 쉬는 시간을 테스트가 직접 흘려보낼 수 있는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
