package com.artifact.diagnosis.controller;

import com.artifact.diagnosis.service.ImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 이미지 업로드 REST API.
 *
 * POST /api/v1/images
 * - Request: multipart/form-data (필드명 "file")
 * - Response: { "url": "Pre-signed URL" }
 */
@Tag(name = "Image", description = "의료 이미지 업로드 API")
@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

    private final ImageStorageService imageStorageService;

    public ImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @Operation(summary = "이미지 업로드", description = "JPEG/PNG/GIF/WebP 이미지를 AWS S3에 업로드하고 1시간 유효한 Pre-signed URL을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "업로드 성공 — Pre-signed URL 반환", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(example = "{\"url\": \"https://s3.amazonaws.com/...\"}"))),
            @ApiResponse(responseCode = "400", description = "파일 없음 또는 지원하지 않는 이미지 형식", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(example = "{\"error\": \"지원하지 않는 이미지 형식입니다.\"}"))),
            @ApiResponse(responseCode = "500", description = "S3 업로드 실패 등 서버 오류", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(example = "{\"error\": \"S3 이미지 업로드에 실패했습니다.\"}")))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(
            @Parameter(description = "업로드할 이미지 파일 (JPEG/PNG/GIF/WebP, 최대 10MB)", required = true) @RequestParam("file") MultipartFile file) {
        String url = imageStorageService.upload(file);
        return ResponseEntity.ok(Map.of("url", url));
    }


}