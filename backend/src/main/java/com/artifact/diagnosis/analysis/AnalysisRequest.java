package com.artifact.diagnosis.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "AI 분석 요청")
public record AnalysisRequest(
        @NotEmpty
        @Schema(description = "분석할 이미지 ID 목록 (visit_image.image_id)", example = "[1, 2]")
        List<Long> imageIds
) {}
