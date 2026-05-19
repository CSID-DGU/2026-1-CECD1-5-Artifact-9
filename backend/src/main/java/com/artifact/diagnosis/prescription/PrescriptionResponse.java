package com.artifact.diagnosis.prescription;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "처방 조회 응답")
public record PrescriptionResponse(
        Long prescriptionId,
        Long visitId,
        Long kcdDiseaseId,
        String kcdCode,
        String kcdNameKr,
        Long analysisId,
        LocalDateTime prescribedAt,
        LocalDate revisitRecommendedDate,
        String doctorNotes,
        List<DetailResponse> details
) {
    public record DetailResponse(
            Long detailId,
            Long drugId,
            String medicineName,
            String dosage,
            Integer durationDays,
            String notes
    ) {}
}
