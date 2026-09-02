package com.artifact.diagnosis.common.exception;

import com.artifact.diagnosis.analysis.AiServiceUnavailableException;
import com.artifact.diagnosis.docshare.ShareLinkExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *   403  직책 권한 부족
 *   404  리소스 없음 (Optional 빈 결과)
 *   409  상태 충돌 (Visit 상태 머신 위반 등)
 *   500  그 외
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    /**
     * 감열지 QR 열람 링크의 유효기간 만료 → 410 Gone.
     *
     * 404 로 뭉뚱그리지 않는 이유는 화면에 띄울 안내가 다르기 때문이다. "주소가 잘못됐다"와
     * "기간이 지났으니 병원에 다시 문의하라"는 환자가 해야 할 행동이 서로 다르다.
     * 토큰이 base62 12자 난수라, 만료됐음을 알려주는 것으로 새어 나가는 정보는 없다 —
     * 애초에 그 토큰을 아는 사람은 종이를 손에 든 사람뿐이다.
     */
    @ExceptionHandler(ShareLinkExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleShareLinkExpired(ShareLinkExpiredException e) {
        return ResponseEntity.status(HttpStatus.GONE).body(error(410, e.getMessage(), null));
    }

    /** Visit 상태 전이 위반 등 → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(409, e.getMessage(), null));
    }

    /**
     * 외부 AI 서버(FastAPI)가 응답하지 않음 → 503.
     *
     * 우리 코드의 버그가 아니라 상대 서버의 일시적 장애다. 500으로 뭉뚱그리면
     * 로그만 봐서는 원인이 어느 쪽인지 알 수 없어, 상태 코드로 분리해 둔다.
     */
    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAiUnavailable(AiServiceUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(503, e.getMessage(), null));
    }

    /**
     * 직책 권한 부족(@StaffAccess/@MedicalAccess/@DoctorAccess 거부) → 403.
     *
     * 이 핸들러가 없으면 403이 아니라 500이 나간다. 메서드 시큐리티가 던지는
     * {@code AuthorizationDeniedException}은 컨트롤러 호출 시점에 발생하므로 이 @RestControllerAdvice가
     * 먼저 잡아버리고, 아무 핸들러도 없으면 맨 아래 {@code handleEtc(Exception)}로 떨어지기 때문이다.
     * Spring Security의 403 변환기(ExceptionTranslationFilter)는 필터 레벨이라 여기까지 오지 못한다.
     *
     * 500으로 나가면 두 가지가 망가진다 — 프론트가 "권한 없음"과 "서버 고장"을 구분하지 못하고,
     * 모니터링에서 정상적인 권한 차단이 장애 알람으로 잡힌다.
     *
     * 권한 거부는 보안 이벤트이므로 누가 무엇을 시도했는지 남긴다. 감사 로그(G3)의 씨앗이다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.warn("권한 거부 - 계정: {}, 권한: {}, 사유: {}",
                auth != null ? auth.getName() : "(미인증)",
                auth != null ? auth.getAuthorities() : "(없음)",
                e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(403, "이 작업을 수행할 권한이 없습니다.", null));
    }

    /** 그 외 잘못된 인자 → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(error(400, e.getMessage(), null));
    }

    /**
     * 마지막 안전망 — 우리가 예상하지 못한 예외.
     *
     * 예외 내용을 응답에 담지 않는다. 전에는 {@code e.getClass().getSimpleName() + ": " + e.getMessage()}를
     * 그대로 내보냈다. 이건 두 가지로 잘못이다:
     *
     *   - 내부 구조 노출. 메시지에 SQL 문장, 테이블·컬럼명, 파일 경로, 라이브러리 이름이 섞여 나온다.
     *       공격자에게는 다음 수를 정하는 데 쓸 수 있는 정보고, 환자 화면까지 닿을 수 있는 문자열이다.
     *   - 사용자에게 의미가 없다. 화면에 뜨는 "NullPointerException: null"을 보고
     *       접수 담당자가 할 수 있는 일은 없다.
     *
     * 대신 스택트레이스를 서버 로그에 남긴다. 디버깅에 필요한 정보는 개발자가 볼 수 있는 곳에
     * 그대로 있고, 밖으로는 나가지 않는다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleEtc(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.internalServerError()
                .body(error(500, "서버 내부 오류가 발생했습니다.", null));
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
