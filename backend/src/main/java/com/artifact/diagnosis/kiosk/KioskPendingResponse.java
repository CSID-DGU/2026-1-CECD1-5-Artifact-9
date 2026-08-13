package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 이 응답은 인증 없이 나가므로 토큰 외에는 아무것도 담지 않는다.
 * 환자 실명·접수번호는 토큰으로 이동한 뒤 세션 조회(KioskSessionResponse)에서 받는다.
 */
@Schema(description = "키오스크 대기화면 폴링 응답 — 새로 접수되어 예비분석이 필요한 환자")
public record KioskPendingResponse(
        @Schema(description = "QR 없이 자동 진입할 때 이동할 /kiosk/{token} 경로의 토큰")
        String kioskToken
) {}
