package com.artifact.diagnosis.certificate;

import java.time.LocalDateTime;

/**
 * 발급이력 목록 한 줄. 실제 병원의 제증명 발급대장에서 눈으로 훑는 항목들이다.
 * 본문 스냅샷은 빼고 보낸다 — 목록에 서류 내용까지 실어 보낼 이유가 없다.
 */
public record CertificateSummaryResponse(
        Long id,
        String serialNo,
        CertificateType type,
        String typeLabel,
        Long visitId,
        String patientName,
        String purpose,
        String submitTo,
        String issuerName,
        LocalDateTime issuedAt,
        CertificateStatus status,
        Long reissueOf
) {
    public static CertificateSummaryResponse from(Certificate c) {
        return new CertificateSummaryResponse(
                c.getId(), c.getSerialNo(), c.getType(), c.getType().getLabel(),
                c.getVisitId(),
                c.getContent() == null ? null : c.getContent().patientName(),
                c.getPurpose(), c.getSubmitTo(), c.getIssuerName(), c.getIssuedAt(),
                c.getStatus(), c.getReissueOf()
        );
    }
}
