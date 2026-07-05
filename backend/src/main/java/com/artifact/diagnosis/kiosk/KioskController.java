package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 대기실 키오스크 REST API. 태블릿은 JWT가 없으므로 이 경로는 인증 없이 연다(SecurityConfig 참고).
 * 데모용 — 운영 환경에서는 접수 시 발급하는 1회용 게스트 토큰으로 제한해야 한다.
 */
@Tag(name = "키오스크", description = "대기실 키오스크 예비분석 API (인증 없음)")
@RestController
@RequestMapping("/api/kiosk")
@RequiredArgsConstructor
public class KioskController {

    private final KioskService kioskService;

    @Operation(summary = "대기 환자 폴링", description = "가장 최근 RECEIVED 상태이면서 예비분석이 없는 Visit 1건을 반환한다. 없으면 404.")
    @GetMapping("/pending")
    public KioskPendingResponse pending() {
        return kioskService.findPending();
    }

    @Operation(summary = "키오스크 예비분석", description = "선택한 이미지를 AI 모델(source=clinic)로 분석해 preliminary_analysis에 저장한다. Visit 상태는 변경하지 않는다.")
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreliminaryAnalysisResponse analyze(
            @Parameter(description = "접수 ID") @RequestParam Long visitId,
            @Parameter(description = "분석할 이미지 파일") @RequestParam("file") MultipartFile file) {
        return kioskService.analyze(visitId, file);
    }

    @Operation(summary = "예비분석 GradCAM 히트맵 조회")
    @GetMapping("/preliminary/{visitId}/heatmap")
    public ResponseEntity<byte[]> heatmap(@PathVariable Long visitId) {
        return kioskService.getHeatmapContent(visitId);
    }
}
