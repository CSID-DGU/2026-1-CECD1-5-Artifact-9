package com.artifact.diagnosis.certificate;

/**
 * LLM이 만든 서술 칸 초안.
 *
 * {@code generated=false} 여도 에러가 아니다. AI 초안은 타자를 대신 쳐주는 편의 기능이지
 * 발급의 전제 조건이 아니므로, 실패하면 의사가 직접 쓰면 된다. 그래서 실패 사유를
 * {@code message} 로 알려주고 화면은 수기 작성으로 계속 진행한다.
 */
public record CertificateDraftResponse(
        String opinion,
        String treatmentPlan,
        String referralReason,

        /** 초안을 만든 모델 식별자. 발급 기록에 함께 남는다. */
        String model,

        /** 실제로 초안이 만들어졌으면 true. */
        boolean generated,

        /** 실패했을 때의 사유, 또는 성공 시 검토 안내. */
        String message
) {

    static final String DISCLAIMER = "AI가 작성한 초안입니다. 반드시 의사가 검토·수정한 뒤 발급하십시오.";

    public static CertificateDraftResponse unavailable(String reason) {
        return new CertificateDraftResponse(null, null, null, null, false, reason);
    }
}
