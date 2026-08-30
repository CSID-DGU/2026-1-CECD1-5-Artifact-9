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

        /**
         * AI 신뢰도 등급 — {@code "low"} 면 화면에 확신도 경고를 띄운다.
         *
         * 예전에는 신뢰도가 낮으면 이 응답 대신 422 가 나갔다. 그 차단을 걷어낸 이유는
         * fastapi/main.py 의 LOW_CONFIDENCE_THRESHOLD 주석에 있다 — 요약하면, 차단은
         * 애매한 것부터 걷어내는데 애매한 쪽에 악성이 몰려 있었다.
         */
        @Schema(description = "AI 신뢰도 등급", example = "normal", allowableValues = {"low", "normal"})
        String confidenceLevel,

        @Schema(description = "확신도가 낮을 때 화면에 띄울 경고 문구. 경고할 것이 없으면 null")
        String caution,

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
