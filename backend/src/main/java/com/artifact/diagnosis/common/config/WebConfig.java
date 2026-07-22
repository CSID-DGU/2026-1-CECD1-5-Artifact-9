package com.artifact.diagnosis.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 설정.
 * - Docker nginx 경유(localhost:3000 / Mac LAN IP:3000): 브라우저 기준 same-origin이므로
 *   CORS 헤더 불필요하나, nginx가 Origin 헤더를 그대로 전달하므로 허용 origin을 명시한다.
 *   직접 API 호출이나 Swagger UI 사용을 위해 허용 origin을 명시적으로 유지한다.
 * - 로컬 Vite dev(localhost:5173): vite proxy가 처리하지만 허용 목록에 포함한다.
 * - 대기실 iPad 키오스크 시연: 같은 Wi-Fi의 사설 IP로 접속하므로 RFC1918 대역을 허용한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "http://10.*.*.*:*",
                        "http://172.*.*.*:*",
                        "http://192.168.*.*:*"
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
