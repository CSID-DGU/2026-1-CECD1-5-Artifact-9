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

        /**
         * 어느 모델의 결과인가 — {@link AnalysisType#code()} 문자열. top1/top5 의 병명 코드가 어느 클래스
         * 목록에 속하는지 화면이 이 값으로 안다(analysisType 이 생기기 전 행은 전부 skin_lesion, V10 참고).
         */
        @Schema(description = "분석 종류(모델) 코드", example = "skin_lesion", allowableValues = {"skin_lesion", "facial"})
        String analysisType,

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
        String heatmapImageUrl,

        @Schema(description = "히트맵을 그린 방식(FastAPI HEATMAP_STYLE). 이 기록 전의 분석은 null",
                example = "layercam-flip-jet-v2")
        String heatmapStyle,

        /**
         * 확대 히트맵들. 화면은 전체 이미지 위에 boxNorm 상자를 그리고 같은 번호의 확대 히트맵을 나란히 보여 준다.
         * 확대 보기가 없으면(이 기능 전의 분석·히트맵 실패) 빈 배열이다.
         */
        @Schema(description = "히트맵이 짚은 곳을 확대한 히트맵 목록. 없으면 빈 배열")
        List<HeatmapCropResult> heatmapCrops,

        /**
         * 원본만으로는 신뢰도가 낮아 확대 영역까지 섞어 다시 판정했을 때만 채운다 — 원본 사진만 보고 낸 1순위.
         * 이때 top1·top5 는 섞은 최종 판정이고, confidenceLevel 은 원본 기준 그대로다.
         */
        @Schema(description = "확대 재판정 전, 원본 사진만 본 1순위. 재판정하지 않았으면 null")
        FullImageTop1 fullImageTop1
) {
    public record Top1Result(
            String diseaseCode,
            String diseaseNameKo,
            BigDecimal confidence
    ) {}

    /**
     * @param imageUrl 확대 히트맵 JPEG 경로(인증 필요). 재분석해도 경로가 같으므로 화면은 analyzedAt 을 붙여 캐시를 피한다.
     * @param boxNorm  원본 이미지 기준 [x0, y0, x1, y1], 각 0~1
     * @param peak     그 영역의 히트맵 최댓값(0~1)
     * @param source   "reinfer"(확대 영역을 모델에 다시 넣어 그림) 또는 "zoom"(전체 히트맵을 잘라 키움).
     *                 화면의 설명 문구가 이 값을 따른다. 이 필드 전의 기록은 null
     */
    public record HeatmapCropResult(
            int rank,
            String imageUrl,
            List<Double> boxNorm,
            Double peak,
            String source
    ) {}

    public record FullImageTop1(
            String diseaseCode,
            String diseaseNameKo,
            double confidence
    ) {}

    public record TopKResult(
            int rank,
            String diseaseCode,
            String diseaseNameKo,
            double confidence,
            String reason
    ) {}
}
