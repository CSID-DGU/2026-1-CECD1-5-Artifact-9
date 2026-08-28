package com.artifact.diagnosis.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 배포 도메인에서 온 요청이 CORS 로 막히지 않는지 고정한다.
 *
 * <p><b>왜 필요한가.</b> 브라우저는 주소창과 같은 출처로 보내는 요청이라도 POST 에는
 * {@code Origin} 헤더를 붙인다. Caddy·nginx 는 그 헤더를 백엔드까지 그대로 전달하므로,
 * 허용 목록에 배포 도메인이 없으면 Spring 이 {@code 403 Invalid CORS request} 로 거절한다.
 * 로그인부터 막히는데, <b>로컬 개발에서는 절대 재현되지 않는다</b> — 로컬 출처는 목록에
 * 들어 있기 때문이다. 실제로 HTTPS 도메인을 붙인 뒤 운영에서만 로그인이 깨진 적이 있다.
 *
 * <p>그래서 "설정이 주입되면 통과하고, 모르는 출처는 여전히 거절된다"를 여기서 못 박는다.
 *
 * <p><b>preflight(OPTIONS)는 여기서 다루지 않는다.</b> 현재는 프론트와 API 가 같은 출처
 * (nginx 가 {@code /api/} 를 프록시)라 브라우저가 preflight 를 아예 보내지 않는다.
 * 실제로 지금 {@code OPTIONS} 를 보내면 SecurityConfig 에 CORS 가 연결돼 있지 않아
 * JwtFilter 가 401 로 막는다. <b>프론트를 별도 도메인에 올리는 순간 모든 API 가 깨진다</b> —
 * 그때는 SecurityConfig 에 {@code .cors(Customizer.withDefaults())} 를 추가하고
 * preflight 테스트를 여기에 되살려야 한다.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artifact_cors_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"cloud.aws.credentials.access-key=test-access-key",
		"cloud.aws.credentials.secret-key=test-secret-key",
		"cloud.aws.s3.bucket=test-bucket",
		"image.storage.type=local",
		"jwt.secret=test-only-dummy-signing-key-not-used-anywhere-else-0123456789",
		"fastapi.internal-secret=test-only-internal-secret",
		// docker-compose.yml 이 실제로 넣어주는 값에 해당한다.
		"cors.allowed-origins=https://artifact-prod.duckdns.org"
})
class CorsConfigTest {

	private static final String DEPLOYED_ORIGIN = "https://artifact-prod.duckdns.org";
	private static final String CORS_REJECTION_BODY = "Invalid CORS request";

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("배포 도메인에서 온 로그인 요청은 CORS 로 막히지 않는다")
	void deployedOriginIsAllowed() throws Exception {
		String body = mockMvc.perform(post("/api/v1/auth/login")
						.header("Origin", DEPLOYED_ORIGIN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"nobody\",\"password\":\"wrong-password\"}"))
				.andReturn().getResponse().getContentAsString();

		// 자격증명이 틀렸으니 로그인 자체는 실패한다. 중요한 것은 그 실패가
		// "CORS 로 거절" 이 아니라 인증 로직까지 도달한 결과여야 한다는 점이다.
		assertThat(body).doesNotContain(CORS_REJECTION_BODY);
	}

	@Test
	@DisplayName("로컬 개발 출처는 설정과 무관하게 계속 허용된다")
	void localOriginStaysAllowed() throws Exception {
		String body = mockMvc.perform(post("/api/v1/auth/login")
						.header("Origin", "http://localhost:5173")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"nobody\",\"password\":\"wrong-password\"}"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain(CORS_REJECTION_BODY);
	}

	@Test
	@DisplayName("허용 목록에 없는 출처는 그대로 거절된다 — 목록이 느슨해지면 여기서 걸린다")
	void unknownOriginIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login")
						.header("Origin", "https://attacker.example.com")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"loginId\":\"nobody\",\"password\":\"wrong-password\"}"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.status().isForbidden());
	}
}
