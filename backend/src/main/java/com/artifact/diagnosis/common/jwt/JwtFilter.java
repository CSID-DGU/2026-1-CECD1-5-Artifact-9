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
 * <p>검증 실패는 삼키지 않고 종류별로 남긴다 — 서명 불일치는 위조 시도일 수 있어 만료·형식오류와
 * 구분해야 하고, 실패를 전부 조용히 넘기면 배포 후 "왜 403이 나는지" 알 방법이 없어진다.
 * 다만 토큰 원문은 절대 로그에 남기지 않는다(그대로 재사용 가능한 자격증명이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                String role = claims.get("role", String.class);
                var auth = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
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
