package com.artifact.diagnosis;

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
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

		PrescriptionResponse saved = prescriptionService.save(visit.getId(), new PrescriptionRequest(
				member.memberId(),
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

}
