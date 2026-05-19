package com.artifact.diagnosis.prescription;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "처방 저장 요청")
public record PrescriptionRequest(

        @NotNull
        @Schema(description = "의사가 확정한 KCD 상병코드 ID", example = "1")
        Long kcdDiseaseId,

        @Schema(description = "근거 AI 분석 ID (선택)", example = "1")
        Long analysisId,

        @Schema(description = "재내원 권장일", example = "2026-06-01")
        LocalDate revisitRecommendedDate,

        @Schema(description = "의사 소견", example = "초기 단계로 판단, 경과 관찰 필요")
        String doctorNotes,

        @NotEmpty
        @Valid
        @Schema(description = "처방 약품 목록 (1개 이상)")
        List<DetailRequest> details
) {
    @Schema(description = "처방 상세 1줄")
    public record DetailRequest(
            @Schema(description = "약품 마스터 ID (선택, 직접 입력 시 생략)", example = "1")
            Long drugId,

            @NotNull
            @Schema(description = "약품명", example = "타크로리무스 연고")
            String medicineName,

            @Schema(description = "용법", example = "1일 2회 도포")
            String dosage,

            @Schema(description = "복용 기간(일)", example = "14")
            Integer durationDays,

            @Schema(description = "주의사항", example = "눈 주위 사용 금지")
            String notes
    ) {}
}
