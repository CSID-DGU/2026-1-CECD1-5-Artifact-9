package com.artifact.diagnosis.visit;

import jakarta.validation.constraints.NotNull;

/**
 * 진료 접수 생성 요청 DTO.
 *
 *   필수: patientId — 접수할 환자 ID
 *   visitDate·status 는 서버에서 자동 설정 (각각 현재 시각, RECEIVED).
 */
public record VisitCreateRequest(
        @NotNull(message = "환자 ID는 필수입니다.") Long patientId, String receptionMemo) {
}
