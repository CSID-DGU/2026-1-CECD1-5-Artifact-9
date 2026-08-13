package com.artifact.diagnosis.prescription;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 처방 저장 요청.
 *
 * <p><b>작성자(memberId)는 여기에 없다.</b> 서버가 인증 토큰에서 직접 꺼내 쓴다
 * ({@code PrescriptionController.save} → {@code PrescriptionService.save}).
 * 다시 필드로 추가하지 말 것 — 진료기록의 작성 의사를 클라이언트가 지정할 수 있게 되는 순간
 * 처방의 법적 책임 주체가 위조 가능해진다.
 */
@Schema(description = "처방 저장 요청 (작성자는 인증 토큰에서 서버가 결정하므로 body에 담지 않는다)")
public record PrescriptionRequest(

        @NotEmpty
        @Valid
        @Schema(description = "상병 목록 (주상병 1개 + 부상병 n개)")
        List<DiseaseRequest> diseases,

        @Schema(description = "근거 AI 분석 ID (선택)", example = "1")
        Long analysisId,

        @Schema(description = "재내원 권장일", example = "2026-06-01")
        LocalDate revisitRecommendedDate,

        @Schema(description = "의사 소견", example = "초기 단계로 판단, 경과 관찰 필요")
        String doctorNotes,

        @Schema(description = "LLM 생성 처방 코멘트 본문 (선택)")
        String aiComment,

        @Schema(description = "LLM 모델 식별자 (선택)", example = "gemini-3.1-flash-lite")
        String aiCommentModel,

        @Schema(description = "LLM 코멘트 생성 시각 — 프론트가 응답을 수신한 시점 (선택)")
        LocalDateTime aiCommentGeneratedAt,

        @Schema(description = "의사가 저장 전 코멘트를 수정했으면 true (선택)")
        Boolean aiCommentEdited,

        @NotEmpty
        @Valid
        @Schema(description = "처방 약품 목록 (1개 이상)")
        List<DetailRequest> details
) {
    public record DiseaseRequest(
            @NotNull Long kcdDiseaseId,
            boolean isPrimary
    ) {}
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
