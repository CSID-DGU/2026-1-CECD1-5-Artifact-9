package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "키오스크 대기화면 폴링 응답 — 새로 접수되어 예비분석이 필요한 환자")
public record KioskPendingResponse(
        Long visitId,
        String patientName,
        String receptionNumber,
        @Schema(description = "QR 없이 자동 진입할 때 이동할 /kiosk/{token} 경로의 토큰")
        String kioskToken
) {}
