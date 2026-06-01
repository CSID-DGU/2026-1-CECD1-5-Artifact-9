package com.artifact.diagnosis.patient;

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
 * 환자 REST API.
 *
 *   POST /api/v1/patients        - 환자 등록 (접수 화면의 폼 데이터)
 *   GET  /api/v1/patients/{id}   - 단건 조회 (등록 직후 확인용)
 */
@Tag(name = "환자", description = "환자 등록 및 조회 API")
@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @Operation(
            summary = "환자 등록",
            description = "접수 화면 폼 데이터를 받아 환자 정보를 신규 저장한다. 이름은 필수, 나머지는 선택."
    )
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "필수값 누락 또는 형식 오류")
    @PostMapping
    public ResponseEntity<PatientResponse> register(
            @Valid @RequestBody PatientCreateRequest request) {
        PatientResponse response = patientService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "환자 단건 조회",
            description = "환자 ID로 환자 정보를 조회한다. 등록 직후 검증용 또는 진료 화면 진입 시 사용."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "해당 ID의 환자가 없음")
    @GetMapping("/{id}")
    public PatientResponse get(
            @Parameter(description = "환자 ID", example = "1")
            @PathVariable Long id) {
        return patientService.findById(id);
    }

    @Operation(summary = "환자 통합 검색", description = "차트번호(patientId), 이름, 내원일 조건을 조합해 환자를 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/search")
    public List<PatientResponse> searchByConditions(
            @Parameter(description = "차트번호에 해당하는 환자 ID", example = "1")
            @RequestParam(required = false) Long patientId,
            @Parameter(description = "이름 검색어", example = "홍")
            @RequestParam(required = false) String name,
            @Parameter(description = "내원일", example = "2026-06-01")
            @RequestParam(required = false) LocalDate visitDate) {
        return patientService.search(patientId, name, visitDate);
    }

    @Operation(summary = "환자 이름 검색", description = "이름 일부를 포함하는 환자 목록을 반환한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<PatientResponse> search(
            @Parameter(description = "이름 검색어", example = "홍")
            @RequestParam(defaultValue = "") String name) {
        return patientService.searchByName(name);
    }
}
