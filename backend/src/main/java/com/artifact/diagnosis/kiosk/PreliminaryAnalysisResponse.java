package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.AnalysisResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "키오스크 예비분석 결과 — AI 보조 참고용이며 의학적 진단이 아님")
public record PreliminaryAnalysisResponse(
        List<TopKResult> topK,

        /** AI 신뢰도 등급 — {@code "low"} 면 화면에 확신도 경고를 띄운다. */
        @Schema(description = "AI 신뢰도 등급", example = "normal", allowableValues = {"low", "normal"})
        String confidenceLevel,

        @Schema(description = "확신도가 낮을 때 화면에 띄울 경고 문구. 경고할 것이 없으면 null")
        String caution,

        String gradcamUrl,
        String aiComment,
        LocalDateTime analyzedAt,

        @Schema(description = "히트맵을 그린 방식(FastAPI HEATMAP_STYLE). 이 기록 전의 예비분석은 null",
                example = "layercam-flip-jet-v2")
        String heatmapStyle,

        /**
         * 확대 히트맵들. 경로는 gradcamUrl 처럼 호출자에 맞춰 내려준다 — 태블릿은 토큰 경로(무인증),
         * 의사 화면은 접수 경로(JWT). 없으면 빈 배열이다.
         */
        @Schema(description = "히트맵이 짚은 곳을 확대한 히트맵 목록. 없으면 빈 배열")
        List<AnalysisResponse.HeatmapCropResult> heatmapCrops,

        /** 원본만으로는 신뢰도가 낮아 확대 영역까지 보고 다시 판정했을 때만: 원본 사진만 본 1순위. */
        @Schema(description = "확대 재판정 전, 원본 사진만 본 1순위. 재판정하지 않았으면 null")
        AnalysisResponse.FullImageTop1 fullImageTop1
) {
    public record TopKResult(String diseaseCode, String diseaseNameKo, double confidence) {}
}
