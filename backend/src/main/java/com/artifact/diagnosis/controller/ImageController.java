package com.artifact.diagnosis.controller;

import com.artifact.diagnosis.service.ImageStorageService;
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
@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

    private final ImageStorageService imageStorageService;

    public ImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> upload(
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