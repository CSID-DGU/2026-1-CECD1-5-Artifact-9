package com.artifact.diagnosis.analysis;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "AI 분석 결과")
public record AnalysisResponse(
        Long analysisId,
        Long visitId,
        String modelVersion,
        Top1Result top1,
        List<TopKResult> top5,
        Integer inferenceTimeMs,
        LocalDateTime analyzedAt
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
            double confidence
    ) {}
}
