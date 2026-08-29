package com.artifact.diagnosis.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 필터 단계에서 걸린 요청에 JSON 오류 본문을 돌려준다.
 *
 * 왜 필요한가. 이걸 붙이기 전에는 로그인하지 않은 요청이
 * 본문이 비어 있는 403으로 나갔다. 두 가지가 망가진다:
 *
 *   - 프론트가 "세션이 만료됨"과 "권한이 부족함"을 구분하지 못한다.
 *       전자는 다시 로그인시켜야 하고 후자는 로그인해도 소용없는데, 둘 다 403이면
 *       화면이 할 수 있는 일은 "알 수 없는 오류"를 띄우는 것뿐이다.
 *   - 본문이 비어 있어 보여줄 메시지 자체가 없다. 사용자는 아무 설명 없이
 *       실패한 화면만 본다.
 *
 * Spring Security는 인증 수단(formLogin/httpBasic)을 하나도 설정하지 않으면
 * 기본 진입점으로 {@code Http403ForbiddenEntryPoint}를 쓴다. JWT만 쓰는 우리 구조에서는
 * 그 기본값이 맞지 않으므로 401을 돌려주도록 바꾼다.
 *
 * 본문 형태는 {@code GlobalExceptionHandler}가 내는 것과 같은
 * {@code {timestamp, status, message}} 로 맞춘다 — 프론트가 오류 본문을 한 가지 모양으로만
 * 다루면 되도록.
 *
 * 어느 쪽이 어느 상황인지 정리하면:
 *   - 토큰 없음·만료·위조 → 이 클래스의 {@link #commence} → 401
 *   - 인증됐으나 직책 부족(필터 단계) → 이 클래스의 {@link #handle} → 403
 *   - 인증됐으나 직책 부족(@PreAuthorize) → {@code GlobalExceptionHandler} → 403
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /** 인증 자체가 없거나 실패 → 401. 프론트는 이 코드를 보고 재로그인을 유도한다. */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("미인증 요청 차단: {} {}", request.getMethod(), request.getRequestURI());
        write(response, 401, "로그인이 필요합니다. 다시 로그인해 주세요.");
    }

    /** 인증은 됐는데 권한이 모자람 → 403. 재로그인해도 해결되지 않는다는 뜻이다. */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("권한 거부(필터 단계): {} {}", request.getMethod(), request.getRequestURI());
        write(response, 403, "이 작업을 수행할 권한이 없습니다.");
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status);
        body.put("message", message);

        objectMapper.writeValue(response.getWriter(), body);
    }
}
