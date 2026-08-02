package com.artifact.diagnosis.visit;

import java.time.LocalDateTime;

/**
 * 진료 접수 응답 DTO. 엔티티를 그대로 노출하지 않고 필요한 필드만.
 * 이미지는 별도 visit_image 테이블로 관리 — GET /visits/{id}/images 로 조회.
 */
public record VisitResponse(
        Long id,
        Long patientId,
        LocalDateTime visitDate,
        VisitStatus status,
        String receptionMemo,
        /** 접수 화면이 키오스크 QR을 그릴 때 사용. 구버전 데이터는 null일 수 있다. */
        String kioskToken,
        LocalDateTime createdAt
) {
    /** Visit 엔티티 → 응답 DTO 변환. */
    public static VisitResponse from(Visit visit) {
        return new VisitResponse(
                visit.getId(),
                visit.getPatientId(),
                visit.getVisitDate(),
                visit.getStatus(),
                visit.getReceptionMemo(),
                visit.getKioskToken(),
                visit.getCreatedAt()
        );
    }
}
