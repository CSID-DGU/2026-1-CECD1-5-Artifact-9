package com.artifact.diagnosis.analysis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI 분석", description = "피부 병변 AI 분석 API")
@RestController
@RequestMapping("/api/v1/visits/{visitId}/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @Operation(summary = "AI 분석 요청",
               description = "선택한 이미지를 AI 모델에 전달해 Top-5 병명을 반환합니다. visit 상태: IN_PROGRESS/ANALYZED → ANALYZING → ANALYZED")
    @PostMapping
    public AnalysisResponse analyze(
            @PathVariable Long visitId,
            @Valid @RequestBody AnalysisRequest request) {
        return analysisService.analyze(visitId, request.imageIds());
    }

    @Operation(summary = "최근 분석 결과 조회",
               description = "해당 접수의 가장 최근 AI 분석 결과(Top-5 포함)를 반환합니다.")
    @GetMapping
    public AnalysisResponse getLatest(@PathVariable Long visitId) {
        return analysisService.getLatest(visitId);
    }
}
