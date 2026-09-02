package com.artifact.diagnosis.docshare;

import com.artifact.diagnosis.certificate.Certificate;
import com.artifact.diagnosis.certificate.CertificateDocument;
import com.artifact.diagnosis.certificate.CertificateStatus;
import com.artifact.diagnosis.certificate.CertificateType;

import java.time.LocalDateTime;

/**
 * 감열지 QR 로 들어온 사람에게 보여줄 증명서.
 *
 * {@code CertificateResponse} 를 그대로 쓰지 않는다. 그쪽에는 certificateId, patientId,
 * visitId, issuedBy, aiModel, aiEdited 처럼 내부 식별자와 운영 정보가 들어 있는데,
 * 이 응답은 로그인 없이 나가므로 화면을 그리는 데 실제로 필요한 값만 담는다.
 * 여기 있는 값은 전부 이미 종이(A4)에 인쇄되어 환자가 들고 있는 것들이다.
 *
 * {@code reissued} 가 원본 ID(Long) 가 아니라 boolean 인 것도 같은 이유다.
 * 화면은 "[재발급]" 표시 여부만 알면 되고, 원본의 PK 는 알 필요가 없다.
 */
public record SharedCertificateResponse(
        CertificateType type,
        String typeLabel,
        CertificateStatus status,
        String voidReason,
        boolean reissued,
        CertificateDocument content,

        /** 이 링크가 닫히는 시각. 화면 하단 안내에 그대로 쓴다. */
        LocalDateTime expiresAt
) {
    public static SharedCertificateResponse from(Certificate c, LocalDateTime expiresAt) {
        return new SharedCertificateResponse(
                c.getType(),
                c.getType().getLabel(),
                c.getStatus(),
                c.getVoidReason(),
                c.getReissueOf() != null,
                c.getContent(),
                expiresAt
        );
    }
}
