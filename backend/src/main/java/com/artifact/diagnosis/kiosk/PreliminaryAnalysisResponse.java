package com.artifact.diagnosis.kiosk;

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
        LocalDateTime analyzedAt
) {
    public record TopKResult(String diseaseCode, String diseaseNameKo, double confidence) {}
}
