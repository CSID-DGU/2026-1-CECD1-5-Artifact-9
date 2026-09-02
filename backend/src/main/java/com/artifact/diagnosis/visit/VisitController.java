package com.artifact.diagnosis.visit;

import com.artifact.diagnosis.common.security.DoctorAccess;
import com.artifact.diagnosis.print.PrintService;
import com.artifact.diagnosis.common.security.MedicalAccess;
import com.artifact.diagnosis.common.security.StaffAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 진료 접수 REST API.
 *
 *   POST   /api/v1/visits              - 접수 생성
 *   GET    /api/v1/visits/{id}         - 단건 조회
 *   GET    /api/v1/visits?status=      - 상태별 목록 조회 (기본값: RECEIVED)
 *   PATCH  /api/v1/visits/{id}/start   - 진료 시작 (RECEIVED → IN_PROGRESS)
 *
 * 접수증·진료요약서 감열지 출력은 <b>서비스가 아니라 여기서</b> 호출한다.
 * VisitService 는 클래스 단위 {@code @Transactional} 이라, 그 안에서 프린터를
 * 부르면 프린터가 응답할 때까지 DB 커넥션을 붙들게 된다. 커밋이 끝난 뒤
 * 컨트롤러에서 부르면 그 문제가 없다.
 */
@Tag(name = "접수", description = "진료 접수 API")
@RestController
@RequestMapping("/api/v1/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final PrintService printService;

    @Operation(summary = "진료 접수 생성", description = "환자 ID를 받아 새 접수를 생성합니다. 초기 상태: RECEIVED.")
    @ApiResponse(responseCode = "201", description = "접수 생성 성공")
    @ApiResponse(responseCode = "400", description = "필수값 누락")
    @ApiResponse(responseCode = "404", description = "환자 없음")
    @StaffAccess
    @PostMapping
    public ResponseEntity<VisitResponse> create(
            @Valid @RequestBody VisitCreateRequest request,
            // 접수 화면이 지금 쓰고 있는 키오스크 주소. 이 값이 그대로 종이 QR 이 된다.
            // 없으면(구버전 프론트, curl) print-agent 의 기본 주소가 쓰인다.
            @RequestHeader(value = PrintService.KIOSK_BASE_URL_HEADER, required = false)
            String kioskBaseUrl) {
        VisitResponse created = visitService.create(request);
        // 접수증 자동 출력. 프린터가 꺼져 있어도 접수는 이미 끝났으므로 실패는 로그만 남는다.
        printService.printTicketAsync(created, kioskBaseUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "접수 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @StaffAccess
    @GetMapping("/{id}")
    public VisitResponse get(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        return visitService.findById(id);
    }

    @Operation(summary = "접수 목록 조회", description = "status, patientId 또는 date(yyyy-MM-dd)로 필터링합니다. 기본값: RECEIVED (대기열).")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @StaffAccess
    @GetMapping
    public List<VisitResponse> list(
            @Parameter(description = "상태 필터", example = "RECEIVED")
            @RequestParam(required = false) VisitStatus status,
            @Parameter(description = "환자 ID 필터")
            @RequestParam(required = false) Long patientId,
            @Parameter(description = "내원일 필터", example = "2026-06-01")
            @RequestParam(required = false) LocalDate date) {
        if (patientId != null) {
            return visitService.findByPatientId(patientId);
        }
        if (date != null) {
            return visitService.findByVisitDate(date);
        }
        return visitService.findByStatus(status != null ? status : VisitStatus.RECEIVED);
    }

    @Operation(summary = "진료 시작", description = "접수 상태를 RECEIVED → IN_PROGRESS로 변경합니다. 이후 이미지 업로드 가능.")
    @ApiResponse(responseCode = "200", description = "진료 시작 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @ApiResponse(responseCode = "409", description = "RECEIVED 상태가 아님")
    @MedicalAccess
    @PatchMapping("/{id}/start")
    public VisitResponse startConsultation(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        return visitService.startConsultation(id);
    }

    @Operation(summary = "키오스크 QR 토큰 발급",
               description = "대기실 키오스크 진입용 토큰을 반환합니다. 아직 없으면 새로 발급합니다(멱등). 접수 화면의 'QR 다시 보기'용.")
    @ApiResponse(responseCode = "200", description = "발급 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @StaffAccess // 접수 화면의 'QR 다시 보기'.
    @PostMapping("/{id}/kiosk-token")
    public VisitResponse issueKioskToken(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        return visitService.issueKioskToken(id);
    }

    @Operation(summary = "진단 확정",
               description = "진단을 확정합니다. 상태: IN_PROGRESS 또는 ANALYZED → DIAGNOSED. "
                           + "처방 저장(POST /api/v1/visits/{visitId}/prescription)이 확정 전이를 함께 수행하므로 "
                           + "일반 진료 흐름에서는 호출할 필요가 없습니다.")
    @ApiResponse(responseCode = "200", description = "진단 확정 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @ApiResponse(responseCode = "409", description = "IN_PROGRESS/ANALYZED 상태가 아님")
    @DoctorAccess
    @PatchMapping("/{id}/diagnose")
    public VisitResponse markDiagnosed(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        return visitService.markDiagnosed(id);
    }

    @Operation(summary = "진료 완료", description = "처방 저장 후 진료를 완료합니다. 상태: PRESCRIBED → COMPLETED.")
    @ApiResponse(responseCode = "200", description = "진료 완료 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @ApiResponse(responseCode = "409", description = "PRESCRIBED 상태가 아님")
    @DoctorAccess
    @PatchMapping("/{id}/complete")
    public VisitResponse markCompleted(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        VisitResponse completed = visitService.markCompleted(id);
        // 진료 요약서 자동 출력. 처방 저장 시점이 아니라 완료 시점에 한 번만 뽑는다 —
        // 처방을 고칠 때마다 나오면 같은 종이가 여러 장 쌓인다. 처방만 저장한 상태에서
        // 미리 보고 싶으면 진료 화면의 '진료요약 인쇄' 버튼을 쓴다.
        printService.printVisitSummaryAsync(id);
        return completed;
    }
}
