package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * FastAPI 추론 응답 구조 (snake_case → camelCase 매핑).
 * AnalysisService(확진 분석)와 KioskService(예비분석)가 같은 모양의 응답을 각자 파싱하고
 * 있었다 — 이 record 하나로 합친다.
 */
public record FastApiPredictResponse(
        /** "low" / "normal". 구버전 FastAPI 는 이 필드를 안 보내므로 null 일 수 있다. */
        @JsonProperty("confidence_level") String confidenceLevel,
        @JsonProperty("low_confidence_threshold") Double lowConfidenceThreshold,
        FastApiTop1 top1,
        List<FastApiTop5Item> top5,
        @JsonProperty("heatmap_base64") String heatmapBase64,
        @JsonProperty("model_version") String modelVersion
) {
    /** model_version 컬럼은 NOT NULL 이라, 구버전 FastAPI가 필드를 안 보내는 과도기에도 저장이 깨지면 안 된다. */
    public String modelVersionOrDefault() {
        return modelVersion != null && !modelVersion.isBlank()
                ? modelVersion
                : "unknown";
    }
}
