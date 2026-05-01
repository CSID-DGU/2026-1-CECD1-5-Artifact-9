package com.artifact.diagnosis.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger UI / OpenAPI 문서 설정.
 *
 *   문서 페이지   : http://localhost:8080/swagger-ui.html
 *   OpenAPI JSON : http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Artifact Medical AI API")
                        .description("의료 영상 기반 AI 보조 진단/처방 지원 시스템")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("Team Artifact")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("로컬 개발")
                ));
    }
}
