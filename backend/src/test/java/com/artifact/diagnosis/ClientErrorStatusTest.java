package com.artifact.diagnosis;

import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 잘못 생긴 요청이 500 이 아니라 그 뜻에 맞는 4xx 로 나가는지 고정한다.
 *
 * Spring MVC 는 요청을 컨트롤러에 넘기지 못하면 예외를 던진다 — 경로·쿼리 값의 타입 불일치,
 * 받지 않는 HTTP 메서드, 받지 않는 Content-Type, 빠진 파라미터·파트, 없는 경로. GlobalExceptionHandler 가
 * 이들을 따로 잡기 전에는 전부 맨 아래 handleEtc 로 떨어져 500 과 ERROR 스택트레이스가 나갔다.
 * 키오스크·공개 열람 경로는 인증 없이 열려 있어서, 로그인하지 않은 누구든 메서드나 형식만 바꿔
 * 찔러 보는 것으로 장애처럼 보이는 로그를 쌓을 수 있었다.
 *
 * 상태 코드만 봐도 handleEtc 를 타지 않았음을 안다 — 그 핸들러는 언제나 500 을 낸다.
 * 본문은 다른 오류와 같은 {timestamp, status, message} 모양이어야 한다. 프론트는 4xx 에서
 * 서버 메시지를 그대로 화면에 띄운다(frontend/src/api/errors.ts).
 */
// EndpointAuthorizationTest 와 같은 이유로 컨텍스트를 이 클래스 전용으로 두고 끝나면 바로 닫는다 —
// H2 인메모리 DB 를 다른 클래스와 공유하지 않고, CI 에서 컨텍스트가 쌓여 메모리가 모자라지 않게 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artifact_client_error_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
		// 개발자 셸이나 backend/.env 에 실제 LLM 키가 있어도 테스트가 유료 외부 호출을 하지 않게 비워 둔다.
		"gemini.api.key=",
		"anthropic.api.key=",
		"print.agent.enabled=false",
		"fastapi.internal-secret=test-only-internal-secret"
})
class ClientErrorStatusTest {

	/** 어떤 접수에도 없는 키오스크 토큰. 실제 토큰과 같은 base62 12자 모양이다. */
	private static final String ANY_TOKEN = "ANYTOKEN0001";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtUtil jwtUtil;

	/**
	 * 숫자 자리에 숫자가 아닌 값 → 400. 경로 변수여도 404 가 아니다(GlobalExceptionHandler#handleTypeMismatch 참고).
	 *
	 * 인증이 필요한 경로라 토큰을 실어 보낸다. 토큰이 없으면 Spring MVC 에 닿기 전에 401 로 막혀
	 * 여기서 보려는 것을 볼 수 없다.
	 */
	@Test
	void nonNumericValueForNumericParameterIsBadRequest() throws Exception {
		String staffToken = tokenFor(MemberRole.STAFF);

		mockMvc.perform(get("/api/v1/visits/{visitId}/analysis", "abc")
						.header("Authorization", "Bearer " + staffToken))
				.andExpect(errorBody(400))
				.andExpect(jsonPath("$.message").value(containsString("visitId")));

		// int 범위를 넘는 숫자도 변환에 실패한다 — 글자가 아니어도 같은 400 이다.
		mockMvc.perform(get("/api/v1/visits/{visitId}/analysis/heatmap/crops/{rank}", 1, "99999999999")
						.header("Authorization", "Bearer " + staffToken))
				.andExpect(errorBody(400))
				.andExpect(jsonPath("$.message").value(containsString("rank")));

		// 쿼리 파라미터도 같은 규칙이다.
		mockMvc.perform(get("/api/v1/certificates")
						.header("Authorization", "Bearer " + staffToken)
						.param("patientId", "abc"))
				.andExpect(errorBody(400))
				.andExpect(jsonPath("$.message").value(containsString("patientId")));
	}

	/**
	 * 키오스크 경로에 맞지 않는 메서드 → 405, 받는 메서드는 Allow 헤더로.
	 *
	 * 키오스크 permitAll 은 메서드를 가리지 않는다(SecurityConfig). 그래서 로그인 없이, 없는 토큰으로도
	 * Spring MVC 까지 닿는다 — 누구든 보낼 수 있는 요청이다.
	 */
	@Test
	void wrongMethodOnKioskRouteIsMethodNotAllowed() throws Exception {
		mockMvc.perform(get("/api/kiosk/session/{token}/analyze", ANY_TOKEN))
				.andExpect(errorBody(405))
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));

		mockMvc.perform(delete("/api/kiosk/session/{token}", ANY_TOKEN))
				.andExpect(errorBody(405))
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));

		mockMvc.perform(put("/api/kiosk/session/{token}/heatmap", ANY_TOKEN))
				.andExpect(errorBody(405))
				.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
	}

	/** 받지 않는 Content-Type → 415, 받는 형식은 Accept 헤더로. 둘 다 로그인 없이 닿는 경로다. */
	@Test
	void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
		// multipart 만 받는 키오스크 분석에 JSON 을 보냄.
		mockMvc.perform(post("/api/kiosk/session/{token}/analyze", ANY_TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"file\": \"not-a-file\"}"))
				.andExpect(errorBody(415))
				.andExpect(header().string(HttpHeaders.ACCEPT, containsString(MediaType.MULTIPART_FORM_DATA_VALUE)));

		// JSON 만 읽는 열람 본인 확인에 text/plain 을 보냄.
		mockMvc.perform(post("/api/public/documents/certificate/{token}/verify", "notarealtoken")
						.contentType(MediaType.TEXT_PLAIN)
						.content("1990-01-01"))
				.andExpect(errorBody(415));
	}

	/**
	 * multipart 인데 file 파트가 없음 → 400.
	 *
	 * 파트를 다른 이름으로 보낸 경우다 — 화면이 필드 이름을 바꿨는데 서버를 맞추지 않은 상황이다.
	 * 토큰은 서비스에서 확인하므로, 그보다 먼저 도는 인자 바인딩에서 400 이 나와야 한다.
	 */
	@Test
	void multipartWithoutFilePartIsBadRequest() throws Exception {
		mockMvc.perform(multipart("/api/kiosk/session/{token}/analyze", ANY_TOKEN)
						.file(new MockMultipartFile("image", "skin.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3})))
				.andExpect(errorBody(400))
				.andExpect(jsonPath("$.message").value(containsString("file")));
	}

	/** 필수 쿼리 파라미터가 빠짐 → 400. 무엇이 빠졌는지 메시지에 이름이 나와야 고칠 수 있다. */
	@Test
	void missingRequiredParameterIsBadRequest() throws Exception {
		mockMvc.perform(get("/api/v1/certificates")
						.header("Authorization", "Bearer " + tokenFor(MemberRole.STAFF)))
				.andExpect(errorBody(400))
				.andExpect(jsonPath("$.message").value(containsString("patientId")));
	}

	/**
	 * 매핑되지 않은 경로 → 404.
	 *
	 * 인증이 필요한 경로는 토큰이 있어야 Spring MVC 에 닿는다(없으면 401). permitAll 아래의 빈 자리도 본다 —
	 * {@code /swagger-ui/**} 는 로그인 없이 열려 있고, 운영에서는 Swagger 를 꺼 두어(SWAGGER_ENABLED=false)
	 * 그 아래가 통째로 빈 경로가 된다. 스캐너가 맨 먼저 두드리는 곳이다.
	 */
	@Test
	void unmappedPathIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/no-such-endpoint")
						.header("Authorization", "Bearer " + tokenFor(MemberRole.STAFF)))
				.andExpect(errorBody(404));

		mockMvc.perform(get("/swagger-ui/no-such-file.js"))
				.andExpect(errorBody(404));
	}

	/** GlobalExceptionHandler 가 만드는 공통 오류 본문 {timestamp, status, message} 와 그 상태 코드. */
	private static ResultMatcher errorBody(int status) {
		return ResultMatcher.matchAll(
				status().is(status),
				jsonPath("$.status").value(status),
				jsonPath("$.message").isNotEmpty(),
				jsonPath("$.timestamp").exists());
	}

	private String tokenFor(MemberRole role) {
		return jwtUtil.generate(1L, "client-error-test-" + role.name().toLowerCase(), role.name());
	}
}
