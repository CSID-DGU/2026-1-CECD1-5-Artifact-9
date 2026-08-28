package com.artifact.diagnosis.certificate;

import java.time.LocalDateTime;

/**
 * 발급된 증명서 단건. {@code content} 안에 종이에 찍힐 값이 전부 들어 있어
 * 프론트는 이것만으로 문서를 그대로 다시 그릴 수 있다.
 */
public record CertificateResponse(
        Long id,
        String serialNo,
        Long visitId,
        Long patientId,

        CertificateType type,
        String typeLabel,
        boolean statutory,
        String legalBasis,
        String formCode,

        String purpose,
        String submitTo,

        String issuerName,
        String issuerLicense,
        LocalDateTime issuedAt,

        CertificateStatus status,
        String voidReason,
        LocalDateTime voidedAt,

        /** 재발급이면 원본 증명서 ID. */
        Long reissueOf,

        String aiModel,
        Boolean aiEdited,

        CertificateDocument content
) {
    public static CertificateResponse from(Certificate c) {
        return new CertificateResponse(
                c.getId(), c.getSerialNo(), c.getVisitId(), c.getPatientId(),
                c.getType(), c.getType().getLabel(), c.getType().isStatutory(),
                c.getType().getLegalBasis(), c.getType().getFormCode(),
                c.getPurpose(), c.getSubmitTo(),
                c.getIssuerName(), c.getIssuerLicense(), c.getIssuedAt(),
                c.getStatus(), c.getVoidReason(), c.getVoidedAt(),
                c.getReissueOf(),
                c.getAiModel(), c.getAiEdited(),
                c.getContent()
        );
    }
}
