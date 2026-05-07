package com.artifact.diagnosis.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 모든 컨트롤러 공통 예외 처리.
 *
 *   400  잘못된 요청 (DTO 검증 실패, 잘못된 인자)
 *   404  리소스 없음 (Optional 빈 결과)
 *   409  상태 충돌 (Visit 상태 머신 위반 등)
 *   500  그 외
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** @Valid 검증 실패. 필드별 메시지를 모아 반환. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.badRequest().body(error(400, "입력값 검증 실패", fieldErrors));
    }

    /** Optional.empty → 404. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(404, e.getMessage(), null));
    }

    /** Visit 상태 전이 위반 등 → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(409, e.getMessage(), null));
    }

    /** 그 외 잘못된 인자 → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(error(400, e.getMessage(), null));
    }

    /** 마지막 안전망. 운영에선 스택트레이스 마스킹 권장. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleEtc(Exception e) {
        return ResponseEntity.internalServerError()
                .body(error(500, e.getClass().getSimpleName() + ": " + e.getMessage(), null));
    }

    private Map<String, Object> error(int status, String message, Object details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status);
        body.put("message", message);
        if (details != null) body.put("details", details);
        return body;
    }
}
