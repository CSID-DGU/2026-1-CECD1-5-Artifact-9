package com.artifact.diagnosis.common.config;

import com.artifact.diagnosis.common.jwt.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 설정.
 * JWT 기반 무상태(stateless) 인증. 세션 미사용.
 *
 * <p>인증 없이 여는 경로는 아래 permitAll 목록이 전부다. 그 외 모든 요청은 인증을 요구한다.
 * 환자 이미지·히트맵은 permitAll 대상이 아니다 — visitId/imageId가 순차 정수라
 * 인증이 없으면 번호만 훑어 전체 환자의 병변 사진을 수집할 수 있기 때문이다.
 *
 * <p><b>키오스크 경로는 와일드카드로 열지 않는다.</b> 대기실 태블릿은 JWT가 없어 인증을 걸 수 없지만,
 * `/api/kiosk/**` 로 통째로 열어두면 이후 이 컨트롤러에 엔드포인트를 추가할 때마다
 * 자동으로 공개된다. 그래서 토큰 스코프인 경로만 하나씩 명시한다 —
 * 새 엔드포인트는 기본적으로 막히고, 열려면 여기에 의도적으로 추가해야 한다.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/signup").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // 대기실 태블릿용 — 경로에 든 kiosk_token(base62 12자리 SecureRandom)이
                // 곧 접근 자격이다. 토큰을 모르면 어떤 접수에도 닿을 수 없다.
                .requestMatchers("/api/kiosk/session/*").permitAll()
                .requestMatchers("/api/kiosk/session/*/analyze").permitAll()
                .requestMatchers("/api/kiosk/session/*/heatmap").permitAll()

                // QR 없이 시연할 때의 폴백. 토큰 없이 "다음 대기 환자"를 알려주므로
                // 기본은 비활성이고 KIOSK_AUTO_PENDING=true 일 때만 동작한다(KioskService 참고).
                .requestMatchers("/api/kiosk/pending").permitAll()

                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
