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
 * 인증 없이 여는 경로는 아래 permitAll 목록이 전부다. 그 외 모든 요청은 인증을 요구한다.
 * 환자 이미지·히트맵은 permitAll 대상이 아니다 — visitId/imageId가 순차 정수라
 * 인증이 없으면 번호만 훑어 전체 환자의 병변 사진을 수집할 수 있기 때문이다.
 *
 * 키오스크 경로는 와일드카드로 열지 않는다. 대기실 태블릿은 JWT가 없어 인증을 걸 수 없지만,
 * `/api/kiosk/**` 로 통째로 열어두면 이후 이 컨트롤러에 엔드포인트를 추가할 때마다
 * 자동으로 공개된다. 그래서 토큰 스코프인 경로만 하나씩 명시한다 —
 * 새 엔드포인트는 기본적으로 막히고, 열려면 여기에 의도적으로 추가해야 한다.
 *
 * 차단 응답은 {@link SecurityErrorResponder}가 만든다. 이 설정을 붙이기 전에는
 * 미인증 요청이 본문 없는 403으로 나가서, 프론트가 "세션 만료"와 "권한 부족"을 구분하지 못했다.
 * 401(재로그인 필요)과 403(재로그인해도 안 됨)을 갈라주는 것이 그 클래스의 역할이다.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final SecurityErrorResponder securityErrorResponder;

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

                // 감열지 QR 로 들어오는 환자용 문서 열람 — 읽기 전용이고, 경로에 든
                // share token(base62 12자리 SecureRandom)이 문서 한 건만 연다.
                // 발급번호(2026-000006)처럼 순번인 값은 절대 이 아래에 두지 않는다.
                //
                // 증명서는 토큰만으로 열리지 않는다. GET 은 "링크가 살아 있는가"만 답하고,
                // 내용은 환자 생년월일을 맞춘 POST .../verify 로만 나간다(DocumentShareService).
                // verify 가 POST 인 이유는 생년월일이 URL 에 남지 않게 하려는 것뿐이다.
                .requestMatchers(HttpMethod.GET, "/api/public/documents/certificate/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/public/documents/certificate/*/verify").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/documents/visit-summary/*").permitAll()

                .anyRequest().authenticated()
            )
            // 필터 단계에서 걸린 요청은 @RestControllerAdvice까지 가지 못한다.
            // 여기서 직접 JSON 본문을 만들어주지 않으면 응답이 빈 껍데기로 나간다.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(securityErrorResponder)   // 미인증 → 401
                .accessDeniedHandler(securityErrorResponder)        // 인증됐으나 권한 부족 → 403
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
