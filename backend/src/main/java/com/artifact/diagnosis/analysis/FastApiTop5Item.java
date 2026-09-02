package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

/** FastAPI 추론 응답의 Top-K 항목 1건. AnalysisService/KioskService 가 공용으로 파싱한다. */
public record FastApiTop5Item(
        int rank,
        @JsonProperty("disease_code")    String diseaseCode,
        @JsonProperty("disease_name_ko") String diseaseNameKo,
        double confidence
) {}
