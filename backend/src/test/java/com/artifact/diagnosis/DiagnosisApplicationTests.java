package com.artifact.diagnosis;

import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.member.MemberLoginRequest;
import com.artifact.diagnosis.member.MemberRepository;
import com.artifact.diagnosis.member.MemberResponse;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.member.MemberService;
import com.artifact.diagnosis.member.MemberSignupRequest;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:artifact_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"cloud.aws.credentials.access-key=test-access-key",
		"cloud.aws.credentials.secret-key=test-secret-key",
		"cloud.aws.s3.bucket=test-bucket",
		"image.storage.type=local",
		// 운영 설정에는 jwt.secret 기본값이 없다(미설정 시 기동 실패가 정상 동작).
		// 테스트에서만 쓰는 더미 키 — HS256 요구사항인 256bit 이상을 만족해야 한다.
		"jwt.secret=test-only-dummy-signing-key-not-used-anywhere-else-0123456789"
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
	VisitRepository visitRepository;

	@Autowired
	KcdDiseaseRepository kcdDiseaseRepository;

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

	/** 토큰 없이 처방을 저장할 수 있으면 위의 보장이 통째로 무의미해진다. */
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
				.andExpect(status().isForbidden());
	}

}
