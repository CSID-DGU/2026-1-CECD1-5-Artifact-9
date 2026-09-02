package com.artifact.diagnosis;

import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.common.security.PublicEndpoint;
import com.artifact.diagnosis.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역할 기반 인가(감사 4번)를 고정하는 테스트.
 *
 * 두 가지를 본다.
 *   - 빠뜨림 방지 — 등록된 모든 핸들러가 인가 애노테이션을 갖고 있는가.
 *       새 엔드포인트를 추가하면서 애노테이션을 잊으면 여기서 빌드가 깨진다.
 *   - 실제 차단 — 애노테이션이 붙어 있다고 실제로 막히는 건 아니다.
 *       필터 체인을 태워 직책별 403을 확인한다.
 *
 * 계획서는 이 자리에 ArchUnit을 제안했지만 Gradle 의존성을 늘리지 않고 같은 보장을 얻었다.
 * ArchUnit은 바이트코드를 훑어 "컨트롤러처럼 생긴 클래스"를 찾지만, 이 테스트는 Spring이
 * 실제로 등록한 핸들러 목록을 그대로 읽는다 — 등록되지 않은 코드는 애초에 대상이 아니고,
 * 등록됐는데 빠진 것은 반드시 걸린다.
 */
// 컨텍스트를 이 클래스 전용으로 격리한다. DiagnosisApplicationTests 와 설정값이 완전히
// 같아서 원래는 Spring 이 컨텍스트를 재사용했는데, 그러면 두 클래스가 같은 H2 인메모리
// DB 를 공유하게 되어 한쪽이 넣은 데이터를 다른 쪽이 실행 순서에 따라 우연히 보거나
// 못 보는 숨은 결합이 생긴다. 격리하는 김에 컨텍스트를 즉시 닫아 CI 에서 여러
// SpringBootTest 컨텍스트가 동시에 쌓여 OutOfMemoryError 로 이어지는 것도 막는다
// (build.gradle 의 test 힙 설정 주석 참고).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artifact_endpoint_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
		"fastapi.internal-secret=test-only-internal-secret"
})
class EndpointAuthorizationTest {

	private static final String OUR_PACKAGE = "com.artifact.diagnosis";

	/**
	 * 인증 없이 열려 있어도 되는 컨트롤러. 여기 이름이 늘어난다는 것은 공개 API가 늘어난다는 뜻이라,
	 * 반드시 리뷰에서 근거를 확인해야 한다. 그래서 목록을 테스트에 고정해 둔다.
	 */
	private static final List<String> EXPECTED_PUBLIC_CONTROLLERS = List.of(
			"AuthController",         // 로그인/가입 — 토큰을 받기 위한 경로
			"KioskController",        // 대기실 태블릿 — JWT 대신 접수별 kiosk_token 이 자격
			// 감열지 QR 로 들어오는 환자 — 병원 계정이 없어 인증을 요구할 수 없다.
			// 대신 문서별 share_token(base62 12자)이 자격이고, 읽기 전용이며 기한이 있다.
			"DocumentShareController"
	);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtUtil jwtUtil;

	@Autowired
	RequestMappingHandlerMapping handlerMapping;

	/**
	 * 우리 패키지의 모든 핸들러는 {@code @PreAuthorize} 계열(=@StaffAccess/@MedicalAccess/@DoctorAccess)
	 * 또는 {@code @PublicEndpoint} 중 하나를 반드시 갖는다.
	 *
	 * 둘 다 없다는 것은 "로그인만 하면 누구나 호출 가능"이라는 뜻인데, 그게 의도였다면
	 * {@code @StaffAccess}를 달아 의도를 밝혀야 한다. 아무것도 안 붙은 상태는 결정을 안 한 상태다.
	 */
	@Test
	void everyEndpointDeclaresAuthorization() {
		var unguarded = new TreeSet<String>();

		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			if (!handler.getBeanType().getPackageName().startsWith(OUR_PACKAGE)) {
				continue; // springdoc 등 프레임워크가 등록한 핸들러는 우리 책임이 아니다.
			}
			if (isGuarded(handler) || isDeliberatelyPublic(handler)) {
				continue;
			}
			unguarded.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()
					+ "  ->  " + entry.getKey());
		}

		assertThat(unguarded)
				.as("""
						인가 애노테이션이 없는 엔드포인트가 있다.
						@StaffAccess / @MedicalAccess / @DoctorAccess 중 하나를 붙이거나,
						정말 공개해야 한다면 @PublicEndpoint 를 붙이고 SecurityConfig 의 permitAll 에도 추가할 것.""")
				.isEmpty();
	}

	/**
	 * 공개 컨트롤러는 위 목록이 전부여야 한다.
	 *
	 * {@code @PublicEndpoint}만으로는 부족하다 — 그 애노테이션은 붙이면 통과되므로,
	 * 급한 마음에 아무 컨트롤러에나 붙여 인가를 우회하는 길이 열린다. 목록을 함께 고정해
	 * 공개 대상을 늘리려면 이 테스트를 고쳐야만 하게 만든다.
	 */
	@Test
	void publicEndpointsAreOnlyTheExpectedOnes() {
		var actual = new TreeSet<String>();

		for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
			if (handler.getBeanType().getPackageName().startsWith(OUR_PACKAGE)
					&& isDeliberatelyPublic(handler)) {
				actual.add(handler.getBeanType().getSimpleName());
			}
		}

		assertThat(actual)
				.as("무인증 공개 컨트롤러 목록이 바뀌었다. 의도한 변경인지 리뷰에서 확인할 것.")
				.containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC_CONTROLLERS);
	}

	/**
	 * 접수 직원은 진료 행위를 할 수 없다 — 이미지 업로드·AI 분석·진료 시작.
	 *
	 * 존재하지 않는 접수 ID로 호출한다. 인가가 걸려 있으면 서비스 로직에 닿기 전에 403이므로
	 * 데이터를 만들 필요가 없고, 인가가 빠져 있으면 404가 나와 테스트가 실패한다.
	 */
	@Test
	void staffCannotPerformMedicalActions() throws Exception {
		String staffToken = tokenFor(MemberRole.STAFF);

		mockMvc.perform(post("/api/v1/visits/{visitId}/analysis", 999_999L)
						.header("Authorization", "Bearer " + staffToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"imageIds\": [1]}"))
				.andExpect(status().isForbidden());

		mockMvc.perform(patch("/api/v1/visits/{id}/start", 999_999L)
						.header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/v1/visits/{visitId}/preliminary", 999_999L)
						.header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isForbidden());
	}

	/**
	 * 간호사는 진료를 도울 수 있지만 처방·진단 확정은 의사의 행위다.
	 *
	 * 처방 body는 검증을 통과하는 값이어야 한다. 빈 배열을 보내면 {@code @Valid}가
	 * 메서드 시큐리티보다 먼저 돌아 400이 나가고, 그러면 인가가 걸렸는지 확인하지 못한다.
	 * (Spring MVC 순서: 인자 바인딩·검증 → 컨트롤러 호출을 감싼 시큐리티 인터셉터)
	 */
	@Test
	void nurseCannotPrescribeOrFinalizeDiagnosis() throws Exception {
		String nurseToken = tokenFor(MemberRole.NURSE);

		mockMvc.perform(post("/api/v1/visits/{visitId}/prescription", 999_999L)
						.header("Authorization", "Bearer " + nurseToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "diseases": [{"kcdDiseaseId": 1, "isPrimary": true}],
								  "details": [{"medicineName": "보습제"}]
								}
								"""))
				.andExpect(status().isForbidden());

		mockMvc.perform(patch("/api/v1/visits/{id}/diagnose", 999_999L)
						.header("Authorization", "Bearer " + nurseToken))
				.andExpect(status().isForbidden());

		mockMvc.perform(patch("/api/v1/visits/{id}/complete", 999_999L)
						.header("Authorization", "Bearer " + nurseToken))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/v1/prescriptions/doctor-patients")
						.header("Authorization", "Bearer " + nurseToken)
						.param("from", "2026-06-01")
						.param("to", "2026-06-30"))
				.andExpect(status().isForbidden());
	}

	/**
	 * 조회는 전 직책에 열려 있다 — 접수 직원도 환자를 찾아야 접수를 할 수 있다.
	 *
	 * 여기서 확인하는 것은 "차단되지 않는다"이지 "성공한다"가 아니다. 없는 접수를 조회하므로
	 * 404가 정상이며, 403만 아니면 인가는 통과한 것이다.
	 */
	@Test
	void staffCanUseLookupScreens() throws Exception {
		String staffToken = tokenFor(MemberRole.STAFF);

		mockMvc.perform(get("/api/v1/patients").header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/visits").header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/kcd-diseases").header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/drugs").header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/visits/{visitId}/images", 999_999L)
						.header("Authorization", "Bearer " + staffToken))
				.andExpect(status().isNotFound());
	}

	/**
	 * 제증명 — 원무과가 열 수 있는 경로와 의사만 열 수 있는 경로가 갈린다.
	 *
	 * 진단명이 들어가는 서류(진단서·소견서·의뢰서)는 직접 진찰한 의사만 발급할 수 있고
	 * (의료법 제17조), 그 서술 항목을 만드는 AI 초안도 같은 제한을 받는다.
	 * 발급 기록을 무효화하는 것은 진료기록 정정에 준하므로 역시 의사 전용이다.
	 *
	 * 반대로 발급대장 조회는 원무과 업무다 — 실제 병원에서 제증명 발급 창구는 원무과에 있다.
	 */
	@Test
	void staffCannotDraftOrVoidCertificates() throws Exception {
		String staffToken = tokenFor(MemberRole.STAFF);

		mockMvc.perform(post("/api/v1/visits/{visitId}/certificates/draft", 999_999L)
						.header("Authorization", "Bearer " + staffToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\": \"DIAGNOSIS\"}"))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/v1/certificates/{id}/void", 999_999L)
						.header("Authorization", "Bearer " + staffToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\": \"오발급\"}"))
				.andExpect(status().isForbidden());

		// 발급대장 조회는 원무과 업무 — 403이 아니어야 한다(대상이 없어 빈 목록).
		mockMvc.perform(get("/api/v1/certificates")
						.header("Authorization", "Bearer " + staffToken)
						.param("patientId", "999999"))
				.andExpect(status().isOk());
	}

	/**
	 * 감열지 QR 문서 열람은 토큰만으로 열려야 한다.
	 *
	 * 여기서 확인하는 것은 "인증을 요구하지 않는다"이다. 없는 토큰이므로 404 가 정상이고,
	 * 401 이 나오면 환자가 QR 을 찍었을 때 로그인 화면을 보게 된다 — 이 기능이 생긴 이유가
	 * 바로 그 문제였다. SecurityConfig 의 permitAll 에서 이 경로가 빠지면 여기서 잡힌다.
	 */
	@Test
	void patientsCanOpenSharedDocumentsWithoutLogin() throws Exception {
		mockMvc.perform(get("/api/public/documents/certificate/{token}", "notarealtoken"))
				.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/public/documents/visit-summary/{token}", "notarealtoken"))
				.andExpect(status().isNotFound());
	}

	/** 관리자는 사다리 꼭대기 — 의사 전용 경로까지 인가를 통과한다(대상이 없어 404). */
	@Test
	void adminPassesDoctorOnlyAuthorization() throws Exception {
		mockMvc.perform(patch("/api/v1/visits/{id}/diagnose", 999_999L)
						.header("Authorization", "Bearer " + tokenFor(MemberRole.ADMIN)))
				.andExpect(status().isNotFound());
	}

	private String tokenFor(MemberRole role) {
		return jwtUtil.generate(1L, "auth-test-" + role.name().toLowerCase(), role.name());
	}

	/** 메타 애노테이션(@StaffAccess 등)을 통해 붙은 @PreAuthorize 도 찾아낸다. */
	private boolean isGuarded(HandlerMethod handler) {
		return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
				|| AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
	}

	private boolean isDeliberatelyPublic(HandlerMethod handler) {
		return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PublicEndpoint.class)
				|| AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PublicEndpoint.class);
	}
}
