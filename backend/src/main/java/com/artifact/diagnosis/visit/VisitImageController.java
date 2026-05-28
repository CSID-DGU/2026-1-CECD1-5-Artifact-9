package com.artifact.diagnosis.visit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 내원 이미지 REST API.
 *
 *   POST  /api/v1/visits/{id}/images  - 이미지 1장 업로드 (반복 호출로 여러 장)
 *   GET   /api/v1/visits/{id}/images  - 해당 접수 이미지 전체 목록
 */
@Tag(name = "내원 이미지", description = "내원별 이미지 업로드/조회 API")
@RestController
@RequestMapping("/api/v1/visits/{visitId}/images")
@RequiredArgsConstructor
public class VisitImageController {

    private final VisitImageService visitImageService;

    @Operation(summary = "이미지 업로드", description = "진료중(IN_PROGRESS) 상태 접수에 이미지를 업로드합니다. 반복 호출로 여러 장 추가 가능.")
    @ApiResponse(responseCode = "201", description = "업로드 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @ApiResponse(responseCode = "409", description = "진료중 상태가 아님")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VisitImageResponse> upload(
            @Parameter(description = "접수 ID") @PathVariable Long visitId,
            @Parameter(description = "이미지 파일 (JPEG/PNG)", required = true)
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(visitImageService.upload(visitId, file));
    }

    @Operation(summary = "이미지 목록 조회", description = "해당 접수에 업로드된 이미지 전체를 업로드 시각 순으로 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "접수 없음")
    @GetMapping
    public List<VisitImageResponse> list(
            @Parameter(description = "접수 ID") @PathVariable Long visitId) {
        return visitImageService.findByVisitId(visitId);
    }
}
