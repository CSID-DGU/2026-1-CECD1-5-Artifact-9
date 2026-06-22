package com.artifact.diagnosis.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 설정.
 * - Docker nginx 경유(localhost:3000): 브라우저 기준 same-origin이므로 CORS 헤더 불필요하나,
 *   직접 API 호출이나 Swagger UI 사용을 위해 허용 origin을 명시적으로 유지한다.
 * - 로컬 Vite dev(localhost:5173): vite proxy가 처리하지만 허용 목록에 포함한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",  // Docker nginx 프론트엔드
                        "http://localhost:5173"   // Vite 로컬 개발 서버
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
