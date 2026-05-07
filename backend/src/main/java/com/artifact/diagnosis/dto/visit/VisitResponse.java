package com.artifact.diagnosis.dto.visit;

import com.artifact.diagnosis.domain.Visit;
import com.artifact.diagnosis.domain.VisitStatus;

import java.time.LocalDateTime;

/**
 * 진료 접수 응답 DTO. 엔티티를 그대로 노출하지 않고 필요한 필드만.
 * imageUrl·imageUploadedAt 은 이미지 업로드 전까지 null.
 */
public record VisitResponse(
    Long id,
    Long patientId,
    LocalDateTime visitDate,
    VisitStatus status,
    String imageUrl,
    LocalDateTime imageUploadedAt,
    LocalDateTime createdAt
) {
    /** Visit 엔티티 → 응답 DTO 변환. */
    public static VisitResponse from(Visit visit){
        return new VisitResponse(
                visit.getId(),
                visit.getPatientId(),
                visit.getVisitDate(),
                visit.getStatus(),
                visit.getImageUrl(),
                visit.getImageUploadedAt(),
                visit.getCreatedAt()
        );
    }
}
