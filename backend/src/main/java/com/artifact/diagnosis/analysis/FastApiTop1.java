package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

/** FastAPI 추론 응답의 Top-1 항목. AnalysisService/KioskService 가 공용으로 파싱한다. */
public record FastApiTop1(
        int rank,
        @JsonProperty("disease_code")    String diseaseCode,
        @JsonProperty("disease_name_ko") String diseaseNameKo,
        double confidence
) {}
