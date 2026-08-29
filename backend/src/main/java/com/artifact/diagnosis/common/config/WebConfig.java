package com.artifact.diagnosis.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CORS 설정.
 *
 * 왜 same-origin 배포인데도 이 목록이 필요한가. 브라우저는 주소창과 같은 출처로 보내는
 * 요청이라도 POST·PUT 등에는 {@code Origin} 헤더를 붙인다. Caddy 와 nginx 는 그 헤더를 그대로
 * 백엔드까지 전달하므로, 여기 없는 출처면 Spring 이 요청을 거절하고
 * {@code 403 Invalid CORS request} 를 돌려준다 — 로그인 자체가 막힌다.
 * 운영 도메인이 빠져 있으면 로컬에서는 멀쩡하고 배포본에서만 깨지므로 특히 놓치기 쉽다.
 *
 * 구성:
 *   - Docker nginx 경유(localhost:3000 / Mac LAN IP:3000) — 직접 API 호출과 Swagger UI 용.
 *   - 로컬 Vite dev(localhost:5173) — vite proxy 가 처리하지만 목록에 포함한다.
 *   - 대기실 iPad 키오스크 시연 — 같은 Wi-Fi 의 사설 IP 로 접속하므로 RFC1918 대역을 허용한다.
 *   - 배포 도메인 — {@code cors.allowed-origins} 로 주입한다(application.properties 참고).
 *       도메인이 바뀌면 코드가 아니라 {@code CORS_ALLOWED_ORIGINS} 환경변수를 고친다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 개발·시연 환경. 사설 대역이라 인터넷에서 이 출처로 접근할 수 없다. */
    private static final List<String> LOCAL_ORIGIN_PATTERNS = List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://10.*.*.*:*",
            "http://172.*.*.*:*",
            "http://192.168.*.*:*"
    );

    /** 배포 도메인. 쉼표로 여러 개를 줄 수 있다. 비어 있으면 위 개발용 출처만 허용된다. */
    @Value("${cors.allowed-origins:}")
    private String[] deployedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> patterns = new ArrayList<>(LOCAL_ORIGIN_PATTERNS);
        Arrays.stream(deployedOrigins)
                .map(String::trim)
                // 환경변수를 비워두면 빈 문자열 하나가 들어온다. 그대로 두면
                // 출처 없는 요청까지 매칭될 수 있어 걸러낸다.
                .filter(origin -> !origin.isEmpty())
                .forEach(patterns::add);

        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
