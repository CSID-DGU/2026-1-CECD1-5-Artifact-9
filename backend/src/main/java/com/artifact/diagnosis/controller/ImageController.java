package com.artifact.diagnosis.controller;

import com.artifact.diagnosis.service.ImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 이미지 업로드 REST API.
 *
 * POST /api/v1/images
 *   - Request: multipart/form-data (필드명 "file")
 *   - Response: { "url": "Pre-signed URL" }
 */
@Tag(name = "이미지", description = "S3 이미지 업로드 API")
@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

    private final ImageStorageService imageStorageService;

    public ImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @Operation(
            summary = "이미지 업로드",
            description = "사진을 S3 버킷에 업로드하고 다운로드 가능한 Pre-signed URL을 반환한다."
    )
    @ApiResponse(responseCode = "200", description = "업로드 성공",
            content = @Content(schema = @Schema(example = "{\"url\": \"https://...\"}")))
    @ApiResponse(responseCode = "400", description = "파일이 비었거나 형식 오류")
    @ApiResponse(responseCode = "500", description = "S3 업로드 실패")
    @PostMapping
    public ResponseEntity<Map<String, String>> upload(
            @Parameter(description = "업로드할 이미지 파일", required = true)
            @RequestParam("file") MultipartFile file) {
        String url = imageStorageService.upload(file);
        return ResponseEntity.ok(Map.of("url", url));
    }

    /**
     * 입력값 검증 실패 시 400 응답.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * 그 외 예외는 500 응답.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleServerError(RuntimeException e) {
        return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
    }
}