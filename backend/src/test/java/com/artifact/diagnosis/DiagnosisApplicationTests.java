package com.artifact.diagnosis;

import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.kiosk.PreliminaryAnalysis;
import com.artifact.diagnosis.kiosk.PreliminaryAnalysisRepository;
import com.artifact.diagnosis.member.MemberLoginRequest;
import com.artifact.diagnosis.member.MemberRepository;
import com.artifact.diagnosis.member.MemberResponse;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.member.MemberService;
import com.artifact.diagnosis.member.MemberSignupRequest;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.patient.PatientService;
import com.artifact.diagnosis.prescription.PrescriptionPatientSummaryResponse;
import com.artifact.diagnosis.prescription.PrescriptionRequest;
import com.artifact.diagnosis.prescription.PrescriptionResponse;
import com.artifact.diagnosis.prescription.PrescriptionService;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// EndpointAuthorizationTest 와 설정값이 완전히 같아 Spring 이 컨텍스트를 재사용했는데,
// 그러면 두 클래스가 같은 H2 인메모리 DB 를 공유하게 되어 실행 순서에 따라 한쪽이 넣은
// 데이터를 다른 쪽이 우연히 보거나 못 보는 숨은 결합이 생긴다. 격리하는 김에 컨텍스트를
// 즉시 닫아 CI 에서 여러 SpringBootTest 컨텍스트가 동시에 쌓여 OutOfMemoryError 로
// 이어지는 것도 막는다(build.gradle 의 test 힙 설정 주석 참고).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artifact_app_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
		// 운영 설정에는 jwt.secret 기본값이 없다(미설정 시 기동 실패가 정상 동작).
		// 테스트에서만 쓰는 더미 키 — HS256 요구사항인 256bit 이상을 만족해야 한다.
		"jwt.secret=test-only-dummy-signing-key-not-used-anywhere-else-0123456789",
		// fastapi.internal-secret 도 기본값이 없다(같은 이유). 테스트는 FastAPI를 부르지 않으므로 값은 아무거나.
		// 테스트에서는 감열지 프린터를 부르지 않는다 — CI 에는 프린터도 print-agent 도 없다.
		"print.agent.enabled=false",
		"fastapi.internal-secret=test-only-internal-secret"
})
class DiagnosisApplicationTests {

	@Autowired
	MemberService memberService;

	@Autowired
	MemberRepository memberRepository;

	@Autowired
	PrescriptionService prescriptionService;

	@Autowired
	PatientRepository patientRepository;

	@Autowired
	PatientService patientService;

	@Autowired
	VisitRepository visitRepository;

	@Autowired
	KcdDiseaseRepository kcdDiseaseRepository;

	@Autowired
	PreliminaryAnalysisRepository preliminaryAnalysisRepository;

	@Autowired
	JwtUtil jwtUtil;

	@Autowired
	MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	/**
	 * 가입 API 는 인증 없이 호출할 수 있으므로, 요청 본문으로 ADMIN 을 지정할 수 있으면
	 * 누구나 관리자가 된다. 서버에서 거부하고 계정도 만들어지지 않아야 한다.
	 */
	@Test
	void signupRejectsSelfAssignedAdminRole() {
		assertThatThrownBy(() -> memberService.signup(new MemberSignupRequest(
				"intruder", "1234", "침입자", null, null, MemberRole.ADMIN)))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(memberRepository.findByLoginId("intruder")).isEmpty();
	}

	/** ADMIN 차단이 나머지 직책 가입까지 막으면 안 된다. */
	@Test
	void signupAllowsClinicalRoles() {
		assertThat(memberService.signup(new MemberSignupRequest(
				"nurse01", "1234", "박간호", null, "피부과", MemberRole.NURSE)).role())
				.isEqualTo(MemberRole.NURSE.name());

		assertThat(memberService.signup(new MemberSignupRequest(
				"staff01", "1234", "최접수", null, null, MemberRole.STAFF)).role())
				.isEqualTo(MemberRole.STAFF.name());

		// role 미지정 시 기본값은 DOCTOR
		assertThat(memberService.signup(new MemberSignupRequest(
				"doctor01", "1234", "정의사", null, "피부과", null)).role())
				.isEqualTo(MemberRole.DOCTOR.name());
	}

	@Test
	void memberSignupLoginAndPrescriptionStoresMemberName() {
		MemberResponse member = memberService.signup(new MemberSignupRequest(
				"derm01", "1234", "김진료", "LIC-1001", "피부과", null));
		MemberResponse loggedIn = memberService.login(new MemberLoginRequest("derm01", "1234"));

		assertThat(loggedIn.memberId()).isEqualTo(member.memberId());
		assertThat(loggedIn.name()).isEqualTo("김진료");

		Patient patient = patientRepository.save(Patient.builder()
				.name("이환자")
				.birthDate(LocalDate.of(1990, 1, 1))
				.gender(Gender.M)
				.build());
		Visit visit = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 2, 10, 15))
				.status(VisitStatus.DIAGNOSED)
				.build());
		KcdDisease disease = kcdDiseaseRepository.save(KcdDisease.builder()
				.code("L20")
				.nameKr("아토피피부염")
				.build());

		// 작성자는 요청 body가 아니라 별도 인자로 전달된다 — 실서비스에서는 컨트롤러가 JWT에서 꺼내 넣는다.
		PrescriptionResponse saved = prescriptionService.save(visit.getId(), member.memberId(), new PrescriptionRequest(
				List.of(new PrescriptionRequest.DiseaseRequest(disease.getId(), true)),
				null,
				LocalDate.of(2026, 6, 9),
				"경과 관찰",
				null,   // aiComment
				null,   // aiCommentModel
				null,   // aiCommentGeneratedAt
				null,   // aiCommentEdited
				List.of(new PrescriptionRequest.DetailRequest(null, "보습제", "1일 2회", 7, null))
		));

		assertThat(saved.memberId()).isEqualTo(member.memberId());
		assertThat(saved.memberName()).isEqualTo("김진료");
		assertThat(saved.details()).hasSize(1);

		List<PrescriptionPatientSummaryResponse> summaries =
				prescriptionService.findPatientsByDoctorAndDate(
						member.memberId(), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 2));

		assertThat(summaries).hasSize(1);
		assertThat(summaries.get(0).patientName()).isEqualTo("이환자");
		assertThat(summaries.get(0).memberName()).isEqualTo("김진료");
	}

	/**
	 * 처방 작성자는 로그인한 계정으로만 기록되어야 한다.
	 * 요청 본문에 남의 memberId 를 심어 보내도 서버는 토큰의 신원으로 저장해야 하며,
	 * 그렇지 않으면 진료기록부의 작성 의사(= 법적 책임 주체)를 아무나 위조할 수 있다.
	 */
	@Test
	void prescriptionAuthorComesFromTokenNotRequestBody() throws Exception {
		MemberResponse author = memberService.signup(new MemberSignupRequest(
				"derm-author", "1234", "실제작성자", "LIC-2001", "피부과", null));
		MemberResponse victim = memberService.signup(new MemberSignupRequest(
				"derm-victim", "1234", "사칭당한의사", "LIC-2002", "피부과", null));

		Patient patient = patientRepository.save(Patient.builder()
				.name("박환자")
				.birthDate(LocalDate.of(1988, 3, 3))
				.gender(Gender.F)
				.build());
		Visit visit = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 3, 9, 0))
				.status(VisitStatus.DIAGNOSED)
				.build());
		KcdDisease disease = kcdDiseaseRepository.save(KcdDisease.builder()
				.code("L30")
				.nameKr("기타 피부염")
				.build());

		String token = jwtUtil.generate(author.memberId(), "derm-author", MemberRole.DOCTOR.name());

		// 수정 전이라면 body 의 memberId 가 그대로 기록됐다. 지금은 무시되어야 한다.
		String forgedBody = """
				{
				  "memberId": %d,
				  "diseases": [{"kcdDiseaseId": %d, "isPrimary": true}],
				  "details": [{"medicineName": "보습제"}]
				}
				""".formatted(victim.memberId(), disease.getId());

		mockMvc.perform(post("/api/v1/visits/{visitId}/prescription", visit.getId())
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(forgedBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memberId").value(author.memberId()))
				.andExpect(jsonPath("$.memberName").value("실제작성자"));
	}

	/**
	 * 이미지를 고르지 않고 분석을 누르면 500(서버 오류)이 아니라 400(잘못된 요청)이어야 한다.
	 * 500은 "우리 서버가 깨졌다"는 신호라, 사용자 실수에 쓰면 진짜 장애를 찾을 때 방해가 된다.
	 */
	@Test
	void analyzeRejectsEmptyImageIdsWithBadRequest() throws Exception {
		String token = jwtUtil.generate(1L, "derm01", MemberRole.DOCTOR.name());

		mockMvc.perform(post("/api/v1/visits/{visitId}/analysis", 1L)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"imageIds\": []}"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * 키오스크 자동 진입이 잡는 대상은 "예비분석을 아직 안 한 접수 중 가장 최근 1건"이다.
	 *
	 * 태블릿은 3초마다 이 조회를 폴링하다가 대상이 생기면 곧바로 이동한다. 따라서 실제로 태블릿 앞에
	 * 서 있는 사람은 방금 접수한 환자다. 오래된 순으로 고르면, 접수만 하고 태블릿을 쓰지 않은 묵은 접수가
	 * 하나라도 남아 있을 때 태블릿이 그 접수를 영원히 반복해 잡아 새 환자가 아무도 진입하지 못한다.
	 * 이 테스트는 바로 그 "묵은 접수에 갇히지 않는다"를 고정한다.
	 */
	@Test
	void kioskAutoEntryPicksLatestVisitWithoutPreliminaryAnalysis() {
		Patient patient = patientRepository.save(Patient.builder()
				.name("한대기")
				.birthDate(LocalDate.of(1992, 7, 7))
				.gender(Gender.M)
				.build());

		// 묵은 접수 — 접수만 하고 태블릿을 쓰지 않아 예비분석이 없다.
		Visit stale = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 5, 9, 0))
				.status(VisitStatus.RECEIVED)
				.build());
		// 방금 접수한 환자 — 태블릿이 잡아야 할 대상.
		Visit latest = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 5, 11, 0))
				.status(VisitStatus.RECEIVED)
				.build());
		// 가장 최근이지만 이미 예비분석을 마쳤다 → 건너뛰어야 한다.
		Visit done = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 5, 12, 0))
				.status(VisitStatus.RECEIVED)
				.build());
		preliminaryAnalysisRepository.save(PreliminaryAnalysis.builder()
				.visitId(done.getId())
				.source("clinic")
				.analyzedAt(LocalDateTime.of(2026, 6, 5, 12, 5))
				.build());

		List<Visit> picked = visitRepository
				.findLatestWithoutPreliminaryAnalysis(VisitStatus.RECEIVED, Limit.of(1));

		assertThat(picked).hasSize(1);
		assertThat(picked.get(0).getId())
				.isEqualTo(latest.getId())
				.isNotEqualTo(stale.getId());
	}

	/**
	 * 토큰 없이 처방을 저장할 수 있으면 위의 보장이 통째로 무의미해진다.
	 *
	 * 401이어야 한다 — 403이 아니다. 전에는 Spring Security 기본 진입점이
	 * 본문 없는 403을 돌려줬다. 그러면 프론트가 두 상황을 구분할 수 없다:
	 * 세션이 만료됐으니 다시 로그인하면 되는 경우와
	 * 직책이 모자라 다시 로그인해도 소용없는 경우가 같은 응답으로 온다.
	 * 그 결과 토큰이 만료돼도 로그인 화면으로 넘어가지 못하고, 화면은 로그인된 척하면서
	 * 누르는 것마다 조용히 실패했다.
	 *
	 * 본문까지 확인하는 이유는, 상태 코드만 맞고 본문이 비면 사용자에게 띄울 문구가
	 * 없어서 화면에 아무것도 안 뜨기 때문이다. 코드와 본문이 한 쌍으로 계약이다.
	 */
	@Test
	void prescriptionSaveRejectsUnauthenticatedRequest() throws Exception {
		mockMvc.perform(post("/api/v1/visits/{visitId}/prescription", 1L)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "diseases": [{"kcdDiseaseId": 1, "isPrimary": true}],
								  "details": [{"medicineName": "보습제"}]
								}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	/**
	 * 인증이 필요한 조회 경로도 같은 계약을 지키는지 본다.
	 *
	 * 위 테스트가 POST 하나만 보고 있어서, 진입점 설정이 특정 경로에만 걸린 것인지
	 * 전체에 걸린 것인지 구분되지 않는다. 실제로 프론트가 세션 만료를 알아채는 지점은
	 * 대부분 화면 진입 직후의 GET이다.
	 */
	@Test
	void unauthenticatedReadReturnsUnauthorizedWithJsonBody() throws Exception {
		mockMvc.perform(get("/api/v1/patients").param("name", "홍"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	/**
	 * 검색어에 섞인 LIKE 와일드카드가 글자로 취급되는지 확인한다.
	 *
	 * 이 테스트가 없으면 조용히 되돌아갈 수 있는 종류의 수정이다. 누군가 편의를 위해
	 * {@code findByNameContaining} 같은 파생 쿼리로 되돌리는 순간 escape 절이 사라지고,
	 * 검색창에 {@code %} 한 글자만 넣으면 전체 환자 명단이 나오는 상태로 돌아간다.
	 * 화면상으로는 "검색이 잘 된다"로 보여서 눈으로는 잡히지 않는다.
	 */
	@Test
	void patientSearchTreatsWildcardsAsLiteralCharacters() {
		patientRepository.save(Patient.builder()
				.name("와일드카드테스트_김환자").birthDate(LocalDate.of(1990, 1, 1))
				.gender(Gender.M).phone("010-0000-0001").build());
		patientRepository.save(Patient.builder()
				.name("와일드카드테스트_이환자").birthDate(LocalDate.of(1991, 2, 2))
				.gender(Gender.F).phone("010-0000-0002").build());

		// '%' 는 "아무 글자나" 가 아니라 그냥 % 라는 글자여야 한다 → 일치하는 환자가 없다.
		assertThat(patientService.searchByName("%")).isEmpty();

		// '_' 도 마찬가지. 이스케이프가 없으면 "와일드카드테스트" + 아무 글자 1개로 두 명이 걸린다.
		assertThat(patientService.searchByName("와일드카드테스트_")).hasSize(2);
		assertThat(patientService.searchByName("와일드카드테스트_김")).hasSize(1);

		// 이스케이프 문자 자신(!)이 검색어에 들어와도 깨지지 않아야 한다.
		assertThat(patientService.searchByName("!")).isEmpty();

		// 평범한 검색은 그대로 동작한다 — 막느라 기능을 죽이면 안 된다.
		assertThat(patientService.searchByName("김환자")).hasSize(1);
	}

	/**
	 * 접수 메모가 외부 모델(Gemini)로 나가기 전에 식별정보가 지워지는지 확인한다.
	 *
	 * 예전에는 이 메모를 화면이 요청 body 에 실어 보냈고, 서버는 그것을 그대로 프롬프트에
	 * 넣었다. 즉 "김○○ 님 010-…" 같은 메모가 통째로 구글 서버에 남았다. 지금은 서버가
	 * visitId 로 직접 읽고 PiiMasker 를 거치므로, 여기서는 DB 조회와 마스킹이 실제로
	 * 이어져 있는지를 본다 — PiiMaskerTest 는 마스킹 함수만 보고 그 연결은 보지 못한다.
	 */
	@Test
	void receptionMemoIsMaskedBeforeLeavingForExternalModel() {
		Patient patient = patientRepository.save(Patient.builder()
				.name("최유출").birthDate(LocalDate.of(1993, 5, 5))
				.gender(Gender.F).phone("010-5555-6666").build());
		Visit visit = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 10, 11, 0))
				.receptionMemo("최유출 님 010-5555-6666 으로 연락, 851231-2345678, x@y.com")
				.status(VisitStatus.DIAGNOSED)
				.build());

		String masked = prescriptionService.maskedReceptionMemo(visit.getId());

		// 메모의 임상 정보는 살아 있어야 한다 — 지우기만 하면 코멘트 품질이 떨어진다.
		assertThat(masked).contains("연락");
		// 신원을 되짚을 수 있는 조각은 하나도 남지 않아야 한다.
		assertThat(masked).doesNotContain("최유출", "5555", "851231", "x@y.com");
	}

	/** 메모가 비어 있으면 null — 프롬프트에서 그 줄이 통째로 빠진다. */
	@Test
	void maskedReceptionMemoIsNullWhenMemoIsAbsent() {
		Patient patient = patientRepository.save(Patient.builder()
				.name("무메모").birthDate(LocalDate.of(1995, 8, 8))
				.gender(Gender.M).build());
		Visit visit = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 11, 9, 30))
				.status(VisitStatus.DIAGNOSED)
				.build());

		assertThat(prescriptionService.maskedReceptionMemo(visit.getId())).isNull();
	}

	/** 없는 접수를 물으면 조용히 null 을 돌려주지 않고 예외로 알린다. */
	@Test
	void maskedReceptionMemoRejectsUnknownVisit() {
		assertThatThrownBy(() -> prescriptionService.maskedReceptionMemo(999_999L))
				.isInstanceOf(java.util.NoSuchElementException.class);
	}

	/**
	 * 배포가 갈리는 순간을 견디는지 본다.
	 *
	 * 백엔드가 먼저 올라가고 브라우저에 옛 화면이 아직 떠 있으면, 그 화면은 예전 형식대로
	 * receptionMemo 를 body 에 담아 보낸다. 서버가 그걸 400 으로 튕기면 배포 직후 몇 분 동안
	 * AI 코멘트 버튼이 죽는다. 모르는 필드는 조용히 버리고 정상 응답해야 한다 —
	 * 그리고 버려지는 것이 핵심이다. 그 값은 더 이상 프롬프트로 가지 않는다.
	 *
	 * 이 테스트 환경에는 gemini.api.key 가 없어 실제 외부 호출은 일어나지 않는다.
	 * 검증 대상은 요청 파싱과 인가이지 모델 응답이 아니다.
	 */
	@Test
	void commentEndpointIgnoresLegacyReceptionMemoInBody() throws Exception {
		MemberResponse doctor = memberService.signup(new MemberSignupRequest(
				"derm-comment", "1234", "코멘트의사", "LIC-3001", "피부과", null));

		Patient patient = patientRepository.save(Patient.builder()
				.name("정레거시").birthDate(LocalDate.of(1991, 4, 4))
				.gender(Gender.M).build());
		Visit visit = visitRepository.save(Visit.builder()
				.patientId(patient.getId())
				.visitDate(LocalDateTime.of(2026, 6, 12, 14, 0))
				.receptionMemo("정레거시 님 010-7777-8888")
				.status(VisitStatus.DIAGNOSED)
				.build());

		String token = jwtUtil.generate(doctor.memberId(), "derm-comment", MemberRole.DOCTOR.name());

		String legacyBody = """
				{
				  "diseases": [{"kcdCode": "L20", "kcdNameKr": "아토피피부염", "isPrimary": true}],
				  "receptionMemo": "이 값은 더 이상 쓰이지 않는다 010-0000-0000"
				}
				""";

		mockMvc.perform(post("/api/v1/visits/{visitId}/prescription/comment", visit.getId())
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(legacyBody))
				.andExpect(status().isOk());
	}

}
