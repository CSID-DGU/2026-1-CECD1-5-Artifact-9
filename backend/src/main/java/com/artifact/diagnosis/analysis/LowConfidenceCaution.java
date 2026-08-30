package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.disease.Severity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * "AI 확신도가 낮음" 경고 문구를 만든다.
 *
 * <p>2026-08-30 이전에는 신뢰도가 임계값에 못 미치면 결과를 아예 돌려주지 않았다(HTTP 422).
 * 그 차단을 걷어내고 대신 이 경고를 붙인다 — 근거와 실측 수치는 fastapi/main.py 의
 * {@code LOW_CONFIDENCE_THRESHOLD} 주석에 있다. 요지는, 차단은 하필 애매한 것부터
 * 걷어내는데 애매한 쪽에 악성이 몰려 있어서 흑색종 재현율이 89.3% → 86.3% 로 떨어졌다는 것이다.
 *
 * <p><b>등급은 FastAPI 가, 문구는 여기가 정한다.</b> 등급("확신이 낮은가")은 임계값을 소유한
 * FastAPI 만 답할 수 있고, 문구는 심각도에 따라 갈려야 하는데 심각도의 원본은 disease 테이블이라
 * 백엔드가 소유한다. 한쪽에 사본을 두면 언젠가 한쪽만 바뀐다.
 *
 * <p>심각도에 따라 문구를 나누는 이유가 이 클래스의 핵심이다. 악성 예측에까지
 * "참고용으로만 사용하세요"를 붙이면, 확신도가 낮았을 뿐인 **진짜 흑색종 경고**를
 * 의사가 흘려보내게 만든다. 확신이 낮다는 사실이 위험하지 않다는 뜻은 아니다.
 */
@Component
@RequiredArgsConstructor
public class LowConfidenceCaution {

    /** FastAPI 의 CONFIDENCE_LOW / CONFIDENCE_NORMAL 과 약속된 값이자, DB 에 그대로 저장되는 값. */
    public static final String LEVEL_LOW = "low";
    public static final String LEVEL_NORMAL = "normal";

    private static final Set<Severity> DANGEROUS = Set.of(Severity.HIGH, Severity.MEDIUM);

    private static final String CAUTION_DEFAULT =
            "AI 확신도가 낮은 결과입니다. 참고용으로만 사용하시고 반드시 의료진이 직접 확인해 주세요.";

    private static final String CAUTION_DANGEROUS =
            "AI 확신도는 낮지만 악성·전암 가능성이 제시되었습니다. "
            + "확신도가 낮다는 것이 위험하지 않다는 뜻은 아니므로, 참고용으로 넘기지 말고 반드시 직접 확인해 주세요.";

    private final DiseaseRepository diseaseRepository;

    /**
     * FastAPI 가 보낸 등급 문자열을 DB 에 저장할 값으로 정규화한다.
     *
     * <p>"low" 가 아닌 것은 전부 normal 로 접는다. 배포가 엇갈려 구버전 FastAPI 가
     * 이 필드를 아예 안 보내는 동안에도(null) 저장이 깨지지 않아야 하고, 알 수 없는 값이
     * 그대로 들어가면 V6 마이그레이션의 CHECK 제약에 걸려 분석 전체가 실패하기 때문이다.
     * 경고를 못 붙이는 것보다 분석이 죽는 쪽이 훨씬 나쁘다.
     */
    public String normalizeLevel(String rawLevel) {
        return LEVEL_LOW.equals(rawLevel) ? LEVEL_LOW : LEVEL_NORMAL;
    }

    /**
     * 화면에 띄울 경고 문구. 경고할 것이 없으면 {@code null} 을 돌려준다.
     *
     * @param confidenceLevel 저장돼 있거나 FastAPI 가 보낸 등급
     * @param diseaseCode     Top-1 병명 코드 — 문구의 강도를 여기서 가른다
     */
    public String messageFor(String confidenceLevel, String diseaseCode) {
        if (!LEVEL_LOW.equals(confidenceLevel)) {
            return null;
        }
        return isDangerous(diseaseCode) ? CAUTION_DANGEROUS : CAUTION_DEFAULT;
    }

    /**
     * 심각도는 캐시하지 않는다 — disease 는 8행짜리 마스터 테이블이고 조회는 응답당 한 번뿐이라
     * 캐시가 벌어들이는 것이 없다. 반대로 캐시를 두면 심각도를 정정했을 때 재기동 전까지
     * 예전 문구가 계속 나간다. 진료 화면에서 그건 그냥 오작동이다.
     *
     * <p>병명을 못 찾으면 안전한 쪽(위험)으로 판단한다. 모르는 코드가 들어왔다는 것은
     * 모델과 disease 시드가 어긋났다는 뜻이라, 그 상황에서 경고를 약하게 쓸 이유가 없다.
     *
     * <p>{@code map} 을 두 번 거치는 것은 일부러다. {@code disease.severity} 는 NULL 을
     * 허용하는 컬럼인데, 한 줄로 접어 {@code DANGEROUS.contains(getSeverity())} 를 쓰면
     * {@link Set#of} 로 만든 불변 집합이 null 인자에 NPE 를 던진다. 지금 형태는 severity 가
     * NULL 이면 빈 Optional 이 되어 아래 {@code orElse(true)} 로 흘러간다 — 즉 모르면 위험.
     */
    private boolean isDangerous(String diseaseCode) {
        return diseaseRepository.findByDiseaseCode(diseaseCode)
                .map(Disease::getSeverity)
                .map(DANGEROUS::contains)
                .orElse(true);
    }
}
