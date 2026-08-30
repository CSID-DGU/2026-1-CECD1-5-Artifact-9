package com.artifact.diagnosis.prescription;

import java.util.List;

/**
 * AI 처방 코멘트 요청.
 *
 * 접수 메모는 여기에 담지 않는다. 예전에는 클라이언트가 실어 보냈고, 그 값이 마스킹 없이
 * 그대로 Gemini 프롬프트에 들어갔다 — 메모에 적힌 환자 이름·연락처가 외부로 나갔다는 뜻이다.
 * 지금은 서버가 경로의 visitId 로 DB 에서 읽어 {@code PiiMasker} 를 거친다
 * ({@code PrescriptionService#maskedReceptionMemo}).
 */
public record PrescriptionCommentRequest(
        List<DiseaseInfo> diseases
) {
    public record DiseaseInfo(
            String kcdCode,
            String kcdNameKr,
            boolean isPrimary
    ) {}
}
