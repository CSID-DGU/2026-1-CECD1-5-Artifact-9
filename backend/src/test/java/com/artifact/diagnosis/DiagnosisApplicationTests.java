package com.artifact.diagnosis;

import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.doctor.DoctorLoginRequest;
import com.artifact.diagnosis.doctor.DoctorResponse;
import com.artifact.diagnosis.doctor.DoctorService;
import com.artifact.diagnosis.doctor.DoctorSignupRequest;
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
		"image.storage.type=local"
})
class DiagnosisApplicationTests {

	@Autowired
	DoctorService doctorService;

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

	@Test
	void doctorSignupLoginAndPrescriptionStoresDoctorName() {
		DoctorResponse doctor = doctorService.signup(new DoctorSignupRequest(
				"derm01", "1234", "김진료", "LIC-1001", "피부과"));
		DoctorResponse loggedIn = doctorService.login(new DoctorLoginRequest("derm01", "1234"));

		assertThat(loggedIn.doctorId()).isEqualTo(doctor.doctorId());
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
				doctor.doctorId(),
				List.of(new PrescriptionRequest.DiseaseRequest(disease.getId(), true)),
				null,
				LocalDate.of(2026, 6, 9),
				"경과 관찰",
				List.of(new PrescriptionRequest.DetailRequest(null, "보습제", "1일 2회", 7, null))
		));

		assertThat(saved.doctorId()).isEqualTo(doctor.doctorId());
		assertThat(saved.doctorName()).isEqualTo("김진료");
		assertThat(saved.details()).hasSize(1);

		List<PrescriptionPatientSummaryResponse> summaries =
				prescriptionService.findPatientsByDoctorAndDate(
						doctor.doctorId(), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 2));

		assertThat(summaries).hasSize(1);
		assertThat(summaries.get(0).patientName()).isEqualTo("이환자");
		assertThat(summaries.get(0).doctorName()).isEqualTo("김진료");
	}

}
