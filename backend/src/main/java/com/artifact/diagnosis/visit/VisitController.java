package com.artifact.diagnosis.visit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 진료 접수 REST API.
 *
 *   POST   /api/v1/visits              - 접수 생성
 *   GET    /api/v1/visits/{id}         - 단건 조회
 *   GET    /api/v1/visits?status=      - 상태별 목록 조회 (기본값: RECEIVED)
 *   PATCH  /api/v1/visits/{id}/start   - 진료 시작 (RECEIVED → IN_PROGRESS)
 */
@Tag(name = "접수", description = "진료 접수 API")
@RestController
@RequestMapping("/api/v1/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;

    @Operation(summary = "진료 접수 생성", description = "환자 ID를 받아 새 접수를 생성합니다. 초기 상태: RECEIVED.")
    @ApiResponse(responseCode = "201", description = "접수 생성 성공")
    @ApiResponse(responseCode = "400", description = "필수값 누락")
    @ApiResponse(responseCode = "404", description = "환자 없음")
    @PostMapping
    public ResponseEntity<VisitResponse> create(@Valid @RequestBody VisitCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(visitService.create(request));
    }

    @Operation(summary = "접수 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @GetMapping("/{id}")
    public VisitResponse get(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        return visitService.findById(id);
    }

    @Operation(summary = "접수 목록 조회", description = "status로 필터링. 기본값: RECEIVED (대기열).")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public List<VisitResponse> list(
            @Parameter(description = "상태 필터", example = "RECEIVED")
            @RequestParam(defaultValue = "RECEIVED") VisitStatus status) {
        return visitService.findByStatus(status);
    }

    @Operation(summary = "진료 시작", description = "접수 상태를 RECEIVED → IN_PROGRESS로 변경합니다. 이후 이미지 업로드 가능.")
    @ApiResponse(responseCode = "200", description = "진료 시작 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @ApiResponse(responseCode = "409", description = "RECEIVED 상태가 아님")
    @PatchMapping("/{id}/start")
    public VisitResponse startConsultation(
            @Parameter(description = "접수 ID", example = "1") @PathVariable Long id) {
        return visitService.startConsultation(id);
    }
}
