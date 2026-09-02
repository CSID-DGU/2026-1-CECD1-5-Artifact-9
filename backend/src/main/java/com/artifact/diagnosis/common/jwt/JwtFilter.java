package com.artifact.diagnosis.common.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization 헤더의 JWT를 검증해 SecurityContext에 인증을 심는다.
 * 토큰이 없거나 유효하지 않으면 인증을 심지 않고 그대로 통과시키고, 차단은 SecurityConfig가 한다.
 *
 * 심는 principal은 {@link AuthPrincipal} 이다 — loginId뿐 아니라 memberId까지 담는다.
 * "이 요청을 한 사람이 누구인가"를 서버가 토큰에서 직접 알 수 있어야, 처방 작성자 같은 값을
 * 요청 body에서 받지 않고 서버가 채울 수 있다.
 *
 * 검증 실패는 삼키지 않고 종류별로 남긴다 — 서명 불일치는 위조 시도일 수 있어 만료·형식오류와
 * 구분해야 하고, 실패를 전부 조용히 넘기면 배포 후 "왜 403이 나는지" 알 방법이 없어진다.
 * 다만 토큰 원문은 절대 로그에 남기지 않는다(그대로 재사용 가능한 자격증명이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    /**
     * 비동기 재디스패치에서도 인증을 다시 심는다.
     *
     * {@link OncePerRequestFilter} 는 기본적으로 ASYNC 디스패치를 건너뛴다. 요청 하나에
     * 필터를 한 번만 태우자는 취지인데, 응답을 나중에 채우는 요청에서는 그게 문제가 된다.
     * 응답이 완성되는 순간 서블릿 컨테이너가 <b>다른 스레드에서</b> 같은 요청을 다시
     * 태우는데, 그 스레드의 {@code SecurityContextHolder} 는 비어 있다. 그런데 스프링
     * 시큐리티의 인가 필터는 ASYNC 도 그대로 검사하므로, 인증을 심어 줄 이 필터만 빠지면
     * 이미 통과했던 요청이 마지막 순간에 401 로 뒤집힌다.
     *
     * 실제로 그 일이 났다. 감열지 인쇄 큐의 롱 폴링
     * ({@code GET /api/v1/print/jobs/next}, PrintJobQueue 참고)이 25초를 기다린 뒤
     * 매번 401 을 받았고, 인쇄 작업을 건네주는 순간에도 401 이 나가 종이가 나오지 않았다.
     *
     * 재검증 비용은 헤더의 JWT 를 한 번 더 파싱하는 것뿐이고, 그 헤더는 재디스패치된
     * 요청에도 그대로 남아 있다. 동기 요청의 동작은 달라지지 않는다 — 그쪽에는 ASYNC
     * 디스패치가 아예 없다.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                String role = claims.get("role", String.class);
                Long memberId = claims.get("memberId", Long.class);

                if (memberId == null) {
                    // memberId 클레임이 없는 토큰(구버전 발급분 등)은 신원을 특정할 수 없으므로 통과시키지 않는다.
                    // 인증을 심지 않고 넘기면 SecurityConfig가 403으로 막는다 → 재로그인하면 정상 토큰을 받는다.
                    log.warn("memberId 클레임이 없는 JWT: uri={} sub={}", request.getRequestURI(), claims.getSubject());
                } else {
                    var auth = new UsernamePasswordAuthenticationToken(
                            new AuthPrincipal(memberId, claims.getSubject(), role),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (ExpiredJwtException e) {
                // 정상 운영에서도 흔하다(세션 만료). 경고까지 올릴 일은 아니다.
                log.debug("만료된 JWT: uri={}", request.getRequestURI());
            } catch (SignatureException e) {
                // 서명 불일치 = 우리 키로 만들지 않은 토큰. 위조 시도일 수 있어 경고로 남긴다.
                log.warn("JWT 서명 검증 실패: uri={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            } catch (JwtException | IllegalArgumentException e) {
                // 형식 오류, 빈 토큰, 지원하지 않는 알고리즘 등
                log.warn("유효하지 않은 JWT: uri={} reason={}", request.getRequestURI(), e.getClass().getSimpleName());
            }
        }
        chain.doFilter(request, response);
    }
}
