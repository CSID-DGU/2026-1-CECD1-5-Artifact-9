package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.common.jwt.AuthPrincipal;
import com.artifact.diagnosis.common.security.DoctorAccess;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/prescriptions")
@RequiredArgsConstructor
@DoctorAccess // "내가 처방한 환자" 목록 — 의사 본인만 의미가 있는 조회다.
public class PrescriptionQueryController {

    private final PrescriptionService prescriptionService;

    /**
     * 로그인한 의사가 해당 기간에 처방한 환자 목록.
     *
     * <p>조회 대상 의사를 쿼리 파라미터로 받지 않는다 — 받으면 아무 계정이나 doctorId 값만 바꿔가며
     * 다른 의사의 담당 환자 명단(환자 실명 포함)을 순회할 수 있다. 화면 요구사항도 "내 환자"라
     * 클라이언트가 지정할 이유가 없다.
     */
    @Operation(summary = "내 처방 환자 목록",
               description = "로그인한 의사 본인이 기간 내에 처방한 환자 목록을 조회합니다. 대상 의사는 인증 토큰에서 결정됩니다.")
    @GetMapping("/doctor-patients")
    public List<PrescriptionPatientSummaryResponse> findDoctorPatients(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return prescriptionService.findPatientsByDoctorAndDate(principal.memberId(), from, to);
    }
}
