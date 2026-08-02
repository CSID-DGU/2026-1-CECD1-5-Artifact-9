package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * QR로 진입한 태블릿이 촬영 전 띄우는 본인 확인 화면용 응답.
 * 환자가 남의 QR을 잘못 찍은 경우를 이 단계에서 걸러낸다.
 */
@Schema(description = "키오스크 세션 정보 — QR 토큰으로 조회한 접수 1건")
public record KioskSessionResponse(
        Long visitId,
        String patientName,
        String receptionNumber,
        @Schema(description = "이미 예비분석이 완료된 접수인지 여부. true여도 재촬영/재분석은 가능하다.")
        boolean analyzed
) {}
