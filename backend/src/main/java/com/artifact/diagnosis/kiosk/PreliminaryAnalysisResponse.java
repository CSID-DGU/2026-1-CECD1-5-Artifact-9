package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "키오스크 예비분석 결과 — AI 보조 참고용이며 의학적 진단이 아님")
public record PreliminaryAnalysisResponse(
        List<TopKResult> topK,
        String gradcamUrl,
        String aiComment,
        LocalDateTime analyzedAt
) {
    public record TopKResult(String diseaseCode, String diseaseNameKo, double confidence) {}
}
