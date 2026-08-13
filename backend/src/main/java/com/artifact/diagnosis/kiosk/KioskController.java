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
 * 인증 대신, 접수 시 발급되는 접수별 토큰(visit.kiosk_token)으로 접근 대상을 한정한다 —
 * 태블릿은 QR로 받은 /kiosk/{token} 경로로 진입하고, <b>읽기·쓰기 API가 모두 토큰 스코프다.</b>
 *
 * <p>여기에 엔드포인트를 추가할 때는 반드시 {@code /session/{token}/...} 아래에 두고,
 * SecurityConfig의 permitAll 목록에도 명시적으로 추가해야 한다. visitId처럼 추측 가능한 값을
 * 경로에 받으면 같은 LAN의 누구나 임의 환자의 데이터에 닿게 된다.
 */
@Tag(name = "키오스크", description = "대기실 키오스크 예비분석 API (인증 없음)")
@RestController
@RequestMapping("/api/kiosk")
@RequiredArgsConstructor
public class KioskController {

    private final KioskService kioskService;

    @Operation(summary = "대기 환자 폴링",
               description = "QR 없이 자동 진입하는 폴백(/kiosk?auto=1)용. 가장 최근 RECEIVED 상태이면서 예비분석이 없는 Visit의 키오스크 토큰만 반환한다. "
                           + "인증도 토큰도 없이 접근 대상을 알려주는 경로라 기본은 비활성이며, KIOSK_AUTO_PENDING=true 일 때만 동작한다. 비활성이거나 대상이 없으면 404.")
    @GetMapping("/pending")
    public KioskPendingResponse pending() {
        return kioskService.findPending();
    }

    @Operation(summary = "키오스크 세션 조회",
               description = "QR 토큰으로 접수 1건을 조회한다. 태블릿이 촬영 전 본인 확인 화면을 띄우는 데 쓴다. 토큰이 유효하지 않으면 404.")
    @GetMapping("/session/{token}")
    public KioskSessionResponse session(
            @Parameter(description = "접수 시 발급된 키오스크 토큰") @PathVariable String token) {
        return kioskService.findSession(token);
    }

    @Operation(summary = "키오스크 예비분석",
               description = "선택한 이미지를 AI 모델(source=clinic)로 분석해 preliminary_analysis에 저장한다. Visit 상태는 변경하지 않는다.")
    @PostMapping(value = "/session/{token}/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreliminaryAnalysisResponse analyze(
            @Parameter(description = "접수 시 발급된 키오스크 토큰") @PathVariable String token,
            @Parameter(description = "분석할 이미지 파일") @RequestParam("file") MultipartFile file) {
        return kioskService.analyze(token, file);
    }

    @Operation(summary = "예비분석 GradCAM 히트맵 조회",
               description = "방금 분석한 접수의 히트맵을 반환한다. 토큰이 유효하지 않거나 히트맵이 없으면 404.")
    @GetMapping("/session/{token}/heatmap")
    public ResponseEntity<byte[]> heatmap(
            @Parameter(description = "접수 시 발급된 키오스크 토큰") @PathVariable String token) {
        return kioskService.getHeatmapContentByToken(token);
    }
}
