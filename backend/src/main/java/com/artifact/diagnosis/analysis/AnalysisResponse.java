package com.artifact.diagnosis.analysis;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "AI 분석 결과")
public record AnalysisResponse(
        Long analysisId,
        Long visitId,

        /**
         * 이 결과를 만든 근거 이미지({@code visit_image.image_id}) 목록.
         *
         * 화면에서 고른 것 전부가 아니라 모델에 실제로 들어간 것만 담긴다.
         * 2026-08-30 이전 분석은 매핑 기록 자체가 없어 빈 배열이다.
         */
        @Schema(description = "분석에 실제로 사용된 이미지 ID 목록", example = "[1]")
        List<Long> analyzedImageIds,

        String modelVersion,
        Top1Result top1,
        List<TopKResult> top5,
        Integer inferenceTimeMs,
        LocalDateTime analyzedAt,
        String heatmapImageUrl
) {
    public record Top1Result(
            String diseaseCode,
            String diseaseNameKo,
            BigDecimal confidence
    ) {}

    public record TopKResult(
            int rank,
            String diseaseCode,
            String diseaseNameKo,
            double confidence,
            String reason
    ) {}
}
