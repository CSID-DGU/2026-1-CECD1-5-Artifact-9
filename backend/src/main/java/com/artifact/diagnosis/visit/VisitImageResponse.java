package com.artifact.diagnosis.visit;

import java.time.LocalDateTime;

/** 이미지 단건 응답 DTO */
public record VisitImageResponse(
        Long imageId,
        Long visitId,
        String imageUrl,
        LocalDateTime uploadedAt
) {
    public static VisitImageResponse from(VisitImage image) {
        return new VisitImageResponse(
                image.getId(),
                image.getVisitId(),
                image.getImageUrl(),
                image.getUploadedAt()
        );
    }
}
