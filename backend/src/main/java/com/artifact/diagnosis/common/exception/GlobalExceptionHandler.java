package com.artifact.diagnosis.common.exception;

import com.artifact.diagnosis.analysis.AiServiceUnavailableException;
import com.artifact.diagnosis.docshare.ShareLinkExpiredException;
import com.artifact.diagnosis.docshare.ShareVerificationFailedException;
import com.artifact.diagnosis.docshare.ShareVerificationLockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 모든 컨트롤러 공통 예외 처리.
 *
 *   400  잘못된 요청 (DTO 검증 실패, 본문 해석 실패, 잘못된 인자, 경로·쿼리 값 형식 오류, 필수 값 누락)
 *   403  직책 권한 부족
 *   404  리소스 없음 (Optional 빈 결과), 매핑되지 않은 경로
 *   405  경로는 있으나 받지 않는 HTTP 메서드
 *   409  상태 충돌 (Visit 상태 머신 위반 등)
 *   415  받지 않는 요청 본문 형식(Content-Type)
 *   500  그 외
 *
 * 형식 오류·필수 값 누락·405·415·없는 경로는 Spring MVC 가 요청을 컨트롤러에 넘기지 못해 던지는 예외다.
 * 요청이 잘못 생긴 것이지 서버 고장이 아닌데, 따로 잡지 않으면 맨 아래 {@link #handleEtc} 로 떨어져
 * 500 과 ERROR 스택트레이스가 나간다. 키오스크·공개 열람 경로는 인증 없이 열려 있어서, 로그인하지 않은
 * 누구든 메서드나 형식만 바꿔 보내는 것으로 장애처럼 보이는 로그를 쌓을 수 있었다. 그래서 이들은 제 뜻에
 * 맞는 4xx 를 돌려주고 로그는 DEBUG 한 줄만 남긴다.
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

    /**
     * 열람 링크의 본인 확인(생년월일) 실패 → 403 Forbidden.
     *
     * 401 을 쓰지 않는 이유는 프론트 규약 때문이다. {@code apiRequest} 는 401 을 "세션 만료"로 보고
     * 로그아웃 절차를 태우는데, 이 화면에는 애초에 로그인 세션이 없다.
     *
     * {@link #handleAccessDenied} 와 달리 예외 메시지를 그대로 내보낸다. 저쪽은 직원용 화면의
     * 권한 거부라 내부 사정을 알릴 이유가 없지만, 이쪽은 환자에게 "몇 번 남았는지"를 알려줘야
     * 오타를 낸 사람이 다음에 뭘 하면 되는지 안다. 메시지는 서비스가 만든 고정 문구뿐이라
     * 내부 구조가 새어 나갈 여지가 없다.
     */
    @ExceptionHandler(ShareVerificationFailedException.class)
    public ResponseEntity<Map<String, Object>> handleShareVerificationFailed(ShareVerificationFailedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(403, e.getMessage(), null));
    }

    /**
     * 본인 확인 실패 누적으로 잠김 → 429 Too Many Requests.
     *
     * 403 과 분리하는 이유는 환자가 해야 할 행동이 다르기 때문이다 — 이쪽은 다시 입력해도
     * 소용이 없고 기다려야 한다. 프론트도 429 문구를 따로 갖고 있다(api/errors.ts).
     */
    @ExceptionHandler(ShareVerificationLockedException.class)
    public ResponseEntity<Map<String, Object>> handleShareVerificationLocked(ShareVerificationLockedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error(429, e.getMessage(), null));
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
     * 요청 본문을 읽지 못함(JSON 문법 오류, 타입 불일치, 허용되지 않은 enum 코드) → 400.
     *
     * 이 핸들러가 없으면 맨 아래 {@code handleEtc} 로 떨어져 500 이 나간다. 잘못 쓴 본문은 클라이언트의
     * 잘못이지 서버 장애가 아니고, 500 이면 프론트가 "잠시 후 다시 시도"를 안내해 같은 요청을 반복하게 된다.
     *
     * 메시지: {@code @JsonCreator} 가 던진 {@link IllegalArgumentException}(예: {@code AnalysisType} 의 모르는
     * 코드)은 우리가 쓴 문구라 그대로 내보낸다 — {@link #handleBadRequest} 와 같은 규칙이다. 그 밖의 원인은
     * Jackson 내부 메시지(필드 경로·클래스명)가 섞여 있어 {@code handleEtc} 와 같은 이유로 내보내지 않는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        Throwable root = e.getMostSpecificCause();
        String message = root instanceof IllegalArgumentException && root.getMessage() != null
                ? root.getMessage()
                : "요청 본문을 읽을 수 없습니다.";
        return ResponseEntity.badRequest().body(error(400, message, null));
    }

    /**
     * 경로·쿼리 값을 선언된 타입으로 바꾸지 못함 → 400.
     *
     * 예: {@code /api/v1/visits/abc/analysis}(Long 자리에 글자), {@code .../heatmap/crops/99999999999}
     * (int 범위 초과), {@code /api/v1/certificates?patientId=abc}.
     *
     * 경로 변수라도 404 로 보내지 않는다. "abc" 는 없는 접수의 번호가 아니라 애초에 번호가 아니다 —
     * 찾아봤더니 없는 것이 아니라 찾아볼 수조차 없는 요청이다. 프론트 사정도 있다. 404 를 "없으면 null"로
     * 받는 호출부가 있어(api/errors.ts 의 isNotFound), 404 로 내보내면 {@code visits/undefined/...} 같은
     * 화면 쪽 버그가 "아직 데이터 없음"으로 조용히 묻힌다. 400 이면 오류로 드러난다.
     * 경로와 쿼리에 같은 규칙을 쓰므로 값이 어디서 왔는지는 가르지 않는다.
     *
     * 메시지에는 파라미터 이름만 넣고 받은 값은 되돌려주지 않는다. 이름은 Swagger 에 공개된 계약이지만,
     * 값은 요청한 쪽이 넣은 문자열이라 응답에 그대로 비출 이유가 없다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                                  HttpServletRequest request) {
        logRejected(request, e);
        return ResponseEntity.badRequest()
                .body(error(400, "요청 값의 형식이 올바르지 않습니다: " + e.getName(), null));
    }

    /** 필수 쿼리 파라미터가 빠짐(예: patientId 없는 {@code GET /api/v1/certificates}) → 400. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException e,
                                                                      HttpServletRequest request) {
        logRejected(request, e);
        return ResponseEntity.badRequest()
                .body(error(400, "필수 요청 값이 빠졌습니다: " + e.getParameterName(), null));
    }

    /**
     * multipart 요청에 필수 파트가 없음 → 400.
     *
     * 예: file 파트 없이(또는 다른 이름으로) 보낸 키오스크 분석. 파트 이름은 컨트롤러가 정한 값이라
     * 그대로 알려준다 — 무엇을 빠뜨렸는지 알아야 보내는 쪽이 고칠 수 있다.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e,
                                                                 HttpServletRequest request) {
        logRejected(request, e);
        return ResponseEntity.badRequest()
                .body(error(400, "필수 요청 값이 빠졌습니다: " + e.getRequestPartName(), null));
    }

    /**
     * 경로는 있으나 그 HTTP 메서드는 받지 않음 → 405.
     *
     * 예: {@code GET /api/kiosk/session/{token}/analyze}, {@code DELETE /api/kiosk/session/{token}}.
     * 키오스크 permitAll 은 메서드를 가리지 않으므로(SecurityConfig) 로그인 없이도 여기까지 온다.
     *
     * 받는 메서드는 {@code Allow} 헤더로 알린다. 405 에는 반드시 붙여야 하는 헤더이고(RFC 9110 §15.5.6),
     * 그 값은 예외가 이미 들고 있다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                        HttpServletRequest request) {
        logRejected(request, e);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(e.getHeaders())
                .body(error(405, "지원하지 않는 요청 방식입니다.", null));
    }

    /**
     * 요청 본문 형식(Content-Type)을 받지 않음 → 415.
     *
     * 예: multipart 만 받는 키오스크 분석에 JSON 을 보냄, JSON 만 읽는 열람 본인 확인에 text/plain 을 보냄.
     * 받는 형식은 {@code Accept} 헤더로 알린다(RFC 9110 §15.5.16). 그 값도 예외가 들고 있다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                                           HttpServletRequest request) {
        logRejected(request, e);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(e.getHeaders())
                .body(error(415, "지원하지 않는 요청 형식입니다.", null));
    }

    /**
     * 어느 컨트롤러에도 매핑되지 않은 경로 → 404.
     *
     * Spring 6.1(Boot 3.2)부터는 매핑에 걸리지 않은 요청을 정적 리소스 핸들러가 받아
     * {@link NoResourceFoundException} 을 던진다. 컨테이너의 404 로 끝나던 예전과 달리 이 advice 까지
     * 오기 때문에, 따로 잡지 않으면 없는 주소가 {@link #handleEtc} 에서 500 이 된다.
     * 인증이 필요한 경로는 그 전에 401 로 막히지만 permitAll 아래는 로그인 없이 닿는다 — 운영은 Swagger 를
     * 꺼 두어(SWAGGER_ENABLED=false) {@code /swagger-ui/**} 가 통째로 빈 경로다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e,
                                                                HttpServletRequest request) {
        logRejected(request, e);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(404, "요청한 경로를 찾을 수 없습니다.", null));
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

    /**
     * Spring MVC 단계에서 거절한 요청을 남긴다. 보내는 쪽의 잘못이라 DEBUG 다 — 운영(LOG_LEVEL=INFO)에서는
     * 쌓이지 않고, 개발 중에는 어느 요청이 왜 거절됐는지 볼 수 있다.
     */
    private void logRejected(HttpServletRequest request, Exception e) {
        log.debug("요청 거절 - {} {}, 사유: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
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
