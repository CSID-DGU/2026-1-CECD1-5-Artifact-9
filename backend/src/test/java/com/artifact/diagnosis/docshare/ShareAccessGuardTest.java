package com.artifact.diagnosis.docshare;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 생년월일 대입 방어를 고정하는 테스트.
 *
 * 이 장치가 없으면 생년월일 확인은 보안이 아니라 형식이 된다 — 경우의 수가 3만 남짓이라
 * 링크를 손에 넣은 사람이 순서대로 넣어보면 몇 분 안에 뚫린다. 그래서 "몇 번 틀리면 잠기는가"를
 * 코드가 아니라 테스트로 못박아 둔다.
 */
class ShareAccessGuardTest {

    private static final String TOKEN = "abc123XYZ789";

    private ShareAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ShareAccessGuard();
    }

    @Test
    @DisplayName("실패가 없으면 잠기지 않는다")
    void cleanTokenPasses() {
        assertThatCode(() -> guard.checkNotLocked(TOKEN)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("틀릴 때마다 남은 시도 횟수가 줄어든다 — 오타를 낸 환자가 알아야 할 값이다")
    void countsDownRemainingAttempts() {
        assertThat(guard.recordFailure(TOKEN)).isEqualTo(4);
        assertThat(guard.recordFailure(TOKEN)).isEqualTo(3);
        assertThat(guard.recordFailure(TOKEN)).isEqualTo(2);
        assertThat(guard.recordFailure(TOKEN)).isEqualTo(1);
    }

    @Test
    @DisplayName("5번째 실패에서 곧바로 잠긴다")
    void locksOnFifthFailure() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure(TOKEN);
        }
        assertThatThrownBy(() -> guard.recordFailure(TOKEN))
                .isInstanceOf(ShareVerificationLockedException.class)
                .hasMessageContaining("다시 시도");
    }

    @Test
    @DisplayName("잠긴 뒤에는 조회 전 단계에서 막힌다 — 맞는 값을 넣어도 열리지 않아야 대입이 느려진다")
    void staysLockedForSubsequentChecks() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure(TOKEN);
        }
        assertThatThrownBy(() -> guard.recordFailure(TOKEN))
                .isInstanceOf(ShareVerificationLockedException.class);

        assertThatThrownBy(() -> guard.checkNotLocked(TOKEN))
                .isInstanceOf(ShareVerificationLockedException.class);
    }

    @Test
    @DisplayName("확인에 성공하면 기록이 지워진다 — 다음 열람에 남은 실패가 따라붙지 않는다")
    void resetClearsHistory() {
        guard.recordFailure(TOKEN);
        guard.recordFailure(TOKEN);
        guard.reset(TOKEN);

        assertThat(guard.recordFailure(TOKEN)).isEqualTo(4);
    }

    @Test
    @DisplayName("실패는 토큰별로 센다 — 남의 실패가 내 링크를 막으면 안 된다")
    void countsPerToken() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure(TOKEN);
        }
        assertThatThrownBy(() -> guard.recordFailure(TOKEN))
                .isInstanceOf(ShareVerificationLockedException.class);

        assertThatCode(() -> guard.checkNotLocked("zzz999AAA111")).doesNotThrowAnyException();
        assertThat(guard.recordFailure("zzz999AAA111")).isEqualTo(4);
    }
}
