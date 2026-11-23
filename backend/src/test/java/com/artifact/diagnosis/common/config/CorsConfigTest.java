package com.artifact.diagnosis.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배포 도메인에서 온 요청이 CORS 로 막히지 않는지 고정한다.
 *
 * 왜 필요한가. 브라우저는 주소창과 같은 출처로 보내는 요청이라도 POST 에는
 * {@code Origin} 헤더를 붙인다. Caddy·nginx 는 그 헤더를 백엔드까지 그대로 전달하므로,
 * 허용 목록에 배포 도메인이 없으면 Spring 이 {@code 403 Invalid CORS request} 로 거절한다.
 * 로그인부터 막히는데, 로컬 개발에서는 절대 재현되지 않는다 — 로컬 출처는 목록에
 * 들어 있기 때문이다. 실제로 HTTPS 도메인을 붙인 뒤 운영에서만 로그인이 깨진 적이 있다.
 *
 * 그래서 "설정이 주입되면 통과하고, 모르는 출처는 여전히 거절된다"를 여기서 못 박는다.
 *
 * preflight(OPTIONS)도 고정한다. 화면을 CloudFront, API 를 ALB 뒤(api 도메인)에 따로 두면 출처가 달라져
 * 브라우저가 Authorization 헤더를 실은 요청마다 OPTIONS 를 먼저 보낸다. SecurityConfig 에
 * {@code .cors(Customizer.withDefaults())} 가 없으면 그 OPTIONS 가 인증 없는 요청으로 401 이 되어
 * 모든 API 가 막힌다(예전에 이 주석에 적어 둔 그 상태). 아래 preflight 테스트가 그 줄을 지킨다.
 */
// 이 클래스의 컨텍스트가 끝나면 바로 닫는다. CI 는 SpringBootTest 컨텍스트 4개를
// JVM 하나에서 순서대로 띄우는데, 안 닫고 남겨두면 마지막 것을 띄울 때쯤 메모리가 쌓여
// OutOfMemoryError 로 죽는다(build.gradle 의 test 힙 설정 주석 참고).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artifact_cors_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		// db/migration 의 SQL 은 MySQL 방언이라 H2 에서 문법 오류로 죽는다.
		// 테스트 스키마는 바로 위 create-drop 이 엔티티에서 만들어 준다.
		"spring.flyway.enabled=false",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"cloud.aws.credentials.access-key=test-access-key",
		"cloud.aws.credentials.secret-key=test-secret-key",
		"cloud.aws.s3.bucket=test-bucket",
		"image.storage.type=local",
		"jwt.secret=test-only-dummy-signing-key-not-used-anywhere-else-0123456789",
		// 테스트에서는 감열지 프린터를 부르지 않는다 — CI 에는 프린터도 print-agent 도 없다.
		"print.agent.enabled=false",
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

	@Test
	@DisplayName("다른 출처(CloudFront 화면)의 preflight 는 로그인 없이 통과하고 허용 헤더를 돌려준다")
	void preflightFromDeployedOriginPasses() throws Exception {
		mockMvc.perform(options("/api/v1/visits")
						.header("Origin", DEPLOYED_ORIGIN)
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_ORIGIN))
				.andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")))
				.andExpect(header().string("Access-Control-Allow-Headers", containsString("authorization")));
	}

	@Test
	@DisplayName("허용 목록에 없는 출처의 preflight 는 거절된다")
	void preflightFromUnknownOriginIsRejected() throws Exception {
		mockMvc.perform(options("/api/v1/visits")
						.header("Origin", "https://attacker.example.com")
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "authorization"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("401 응답에도 CORS 헤더가 붙는다 — 그래야 다른 출처의 화면이 401 을 읽고 다시 로그인시킨다")
	void unauthorizedResponseCarriesCorsHeader() throws Exception {
		mockMvc.perform(get("/api/v1/visits").param("status", "RECEIVED").header("Origin", DEPLOYED_ORIGIN))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_ORIGIN));
	}
}
