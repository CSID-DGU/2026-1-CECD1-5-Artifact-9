package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 의사 진료 페이지에서 대기 중 자동 생성된 키오스크 예비분석 결과를 조회하는 API.
 * 기존 인증(JWT) 경로에 둔다 — /api/kiosk/** 와 달리 permitAll 대상이 아니다.
 */
@Tag(name = "예비분석 조회", description = "키오스크 예비분석 결과 조회 API (인증 필요)")
@RestController
@RequestMapping("/api/v1/visits/{visitId}/preliminary")
@RequiredArgsConstructor
public class PreliminaryAnalysisController {

    private final KioskService kioskService;

    @Operation(summary = "예비분석 결과 조회", description = "해당 접수의 키오스크 예비분석 결과를 반환한다. 없으면 404.")
    @GetMapping
    public PreliminaryAnalysisResponse get(
            @Parameter(description = "접수 ID") @PathVariable Long visitId) {
        return kioskService.findByVisitId(visitId);
    }
}
