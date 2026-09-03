package com.artifact.diagnosis.docshare;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 열람 링크 본인 확인의 대입 방어.
 *
 * <p><b>왜 필요한가.</b> 생년월일은 비밀번호가 아니다. 실제로 가능한 값은 3만 가지 남짓이고
 * 환자 연령대를 짐작하면 수천 가지로 줄어든다. 토큰이 든 종이를 주운 사람이 자동화 도구로
 * 순서대로 넣어보면 몇 분 안에 맞는다. 그래서 "틀린 횟수"를 세는 장치가 반드시 함께 있어야
 * 생년월일 확인이 의미를 갖는다.
 *
 * <p><b>토큰 단위로 센다.</b> IP 단위로 세면 병원 대기실 와이파이처럼 여러 환자가 같은
 * 출구 IP 를 쓰는 곳에서 남의 실패가 내 열람을 막는다. 공격 대상은 어차피 문서 한 건이므로,
 * 그 문서의 토큰에 실패를 붙이는 편이 정확하고 부작용이 없다.
 *
 * <p><b>메모리에만 둔다.</b> 실패 기록은 DB 에 남길 만한 사실이 아니고(성공하면 지워진다),
 * 이 배포는 백엔드 인스턴스가 하나다. 재시작하면 카운터가 풀리지만, 그걸 노리려면 공격자가
 * 배포 시점을 맞춰야 하고 그때도 창은 {@link #WINDOW} 만큼만 열린다. 인스턴스를 여러 개로
 * 늘릴 때는 이 클래스를 Redis 같은 공유 저장소로 옮겨야 한다 — 그 전까지는 여기가 맞는 위치다.
 */
@Slf4j
@Component
public class ShareAccessGuard {

    /** 이 횟수만큼 틀리면 잠긴다. */
    private static final int MAX_FAILURES = 5;

    /**
     * 실패를 기억하는 시간이자 잠금이 풀리는 시간.
     *
     * 카운터를 영구히 들고 있지 않는 이유는, 진짜 환자가 오타 몇 번으로 링크를 영영 잃는 일을
     * 막기 위해서다. 반대로 공격자 입장에서는 10분에 5번 = 시간당 30번이라 3만 가지를 훑는 데
     * 40일이 넘게 걸린다. 그 사이에 링크 유효기간(기본 7일)이 먼저 끝난다.
     */
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** 이 수를 넘으면 만료된 기록부터 쓸어낸다. 메모리가 무한정 늘지 않게 하는 상한이다. */
    private static final int MAX_TRACKED = 10_000;

    /**
     * @param count   {@link #WINDOW} 안에서 연속으로 틀린 횟수
     * @param retryAt 이 시각이 지나면 기록을 잊는다(=잠금 해제)
     */
    private record Failures(int count, Instant retryAt) {
        boolean locked() {
            return count >= MAX_FAILURES;
        }
    }

    private final Map<String, Failures> byToken = new ConcurrentHashMap<>();

    /**
     * 잠겨 있으면 던진다. 생년월일을 비교하기 <b>전에</b> 부른다 —
     * 잠긴 동안에는 맞는 값을 넣어도 열리지 않아야 대입 시도가 실제로 느려진다.
     */
    public void checkNotLocked(String token) {
        Failures failures = byToken.get(token);
        if (failures == null) {
            return;
        }
        if (Instant.now().isAfter(failures.retryAt())) {
            byToken.remove(token, failures);   // 창이 지났다 — 없던 일로 한다
            return;
        }
        if (failures.locked()) {
            throw new ShareVerificationLockedException(lockMessage(failures.retryAt()));
        }
    }

    /**
     * 틀린 시도 한 번을 기록한다.
     *
     * @return 잠기기까지 남은 시도 횟수. 화면에 그대로 보여준다 — 몇 번 남았는지 알려주는 것은
     *         공격자에게 새로운 정보가 아니고(직접 세면 된다), 오타를 낸 환자에게는 필요한 정보다.
     * @throws ShareVerificationLockedException 이번 실패로 한도를 채운 경우
     */
    public int recordFailure(String token) {
        purgeIfCrowded();

        Instant now = Instant.now();
        Failures updated = byToken.compute(token, (key, previous) -> {
            // 창이 지난 기록은 이어 세지 않고 1부터 다시 센다.
            int carried = (previous == null || now.isAfter(previous.retryAt())) ? 0 : previous.count();
            return new Failures(carried + 1, now.plus(WINDOW));
        });

        if (updated.locked()) {
            log.warn("문서 열람 본인확인 {}회 실패로 잠금 (token 앞 4자리={}...)",
                    updated.count(), token.length() >= 4 ? token.substring(0, 4) : "?");
            throw new ShareVerificationLockedException(lockMessage(updated.retryAt()));
        }
        return MAX_FAILURES - updated.count();
    }

    /** 확인에 성공하면 기록을 지운다. 다음에 또 열어볼 때 남은 실패가 따라붙지 않게 한다. */
    public void reset(String token) {
        byToken.remove(token);
    }

    private String lockMessage(Instant retryAt) {
        long minutes = Math.max(1, Duration.between(Instant.now(), retryAt).toMinutes() + 1);
        return "본인 확인 시도가 너무 많습니다. %d분 후에 다시 시도해 주세요.".formatted(minutes);
    }

    /**
     * 만료된 기록 청소. 주기 실행 스레드를 하나 더 띄우지 않고, 지도가 커졌을 때만 훑는다 —
     * 평상시 이 지도에는 항목이 거의 없다(성공하면 지워지므로).
     */
    private void purgeIfCrowded() {
        if (byToken.size() < MAX_TRACKED) {
            return;
        }
        Instant now = Instant.now();
        byToken.entrySet().removeIf(entry -> now.isAfter(entry.getValue().retryAt()));
    }
}
