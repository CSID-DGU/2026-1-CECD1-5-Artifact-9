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
        @JsonProperty("model_version") String modelVersion,

        /**
         * 실제로 추론한 태스크 코드("skin_lesion"/"facial"). 태스크 개념이 없던 구버전 FastAPI 는 이 필드를
         * 안 보내므로 null 일 수 있다 — 그때는 기본 모델이 돈 것이다(AnalysisService.analyze 가 요청과 대조).
         */
        String task,

        /** 히트맵을 그린 방식(fastapi/main.py HEATMAP_STYLE). 이 필드가 생기기 전의 FastAPI 는 null. */
        @JsonProperty("heatmap_style") String heatmapStyle,

        /** 확대 보기. 구버전 FastAPI·히트맵 실패·확대 보기를 끈 서버(HEATMAP_CROPS=0)는 null 또는 빈 배열. */
        List<FastApiCrop> crops,

        /**
         * 확대 재판정을 했을 때만 온다: 원본 사진만 보고 낸 1순위. top1 과 다를 수 있다.
         * 원본만으로는 신뢰도가 낮아(confidence_level=low) 확대 영역의 판정을 섞은 경우다(fastapi/main.py run_inference).
         */
        @JsonProperty("full_image_top1") FastApiTop1 fullImageTop1
) {
    /** model_version 컬럼은 NOT NULL 이라, 구버전 FastAPI가 필드를 안 보내는 과도기에도 저장이 깨지면 안 된다. */
    public String modelVersionOrDefault() {
        return modelVersion != null && !modelVersion.isBlank()
                ? modelVersion
                : "unknown";
    }
}
