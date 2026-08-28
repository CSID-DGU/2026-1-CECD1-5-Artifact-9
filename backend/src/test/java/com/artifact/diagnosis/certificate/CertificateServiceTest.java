package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.common.jwt.AuthPrincipal;
import com.artifact.diagnosis.member.Member;
import com.artifact.diagnosis.member.MemberRepository;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.prescription.Prescription;
import com.artifact.diagnosis.visit.Visit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 발급 규칙을 고정하는 테스트.
 *
 * <p>컨트롤러의 {@code @StaffAccess} 는 "이 화면에 들어올 수 있는가"까지만 본다.
 * 그 뒤에 걸리는 규칙 — 직접 진찰한 의사인지, 진료가 확정됐는지, 누구 이름으로 서명되는지 —
 * 은 서비스에 있고, 그것이 바로 여기서 검증하는 것이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceTest {

    private static final long ATTENDING_DOCTOR_ID = 10L;
    private static final long OTHER_DOCTOR_ID = 20L;
    private static final long STAFF_ID = 30L;

    @Mock CertificateRepository certificateRepository;
    @Mock CertificateDataAssembler assembler;
    @Mock CertificateDraftService draftService;
    @Mock MemberRepository memberRepository;

    @InjectMocks CertificateService certificateService;

    Member attendingDoctor;
    Member otherDoctor;
    Member staff;

    @BeforeEach
    void setUp() {
        attendingDoctor = member(ATTENDING_DOCTOR_ID, "김담당", "12345");
        otherDoctor = member(OTHER_DOCTOR_ID, "박다른", "67890");
        staff = member(STAFF_ID, "이원무", null);

        when(memberRepository.findById(ATTENDING_DOCTOR_ID)).thenReturn(Optional.of(attendingDoctor));
        when(memberRepository.findById(OTHER_DOCTOR_ID)).thenReturn(Optional.of(otherDoctor));
        when(memberRepository.findById(STAFF_ID)).thenReturn(Optional.of(staff));

        when(assembler.assemble(any(), any(), any(), any())).thenReturn(emptyDocument());
        when(certificateRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Certificate saved = invocation.getArgument(0);
            saved.setId(42L);
            saved.setIssuedAt(LocalDateTime.of(2026, 8, 28, 10, 0));
            return saved;
        });
    }

    @Test
    @DisplayName("진단서는 직접 진료한 의사만 발급할 수 있다 (의료법 제17조)")
    void onlyAttendingDoctorCanIssueDiagnosis() {
        CertificateFacts facts = factsWithPrescription();
        when(assembler.loadFacts(1L)).thenReturn(facts);

        assertThatThrownBy(() -> certificateService.issue(1L, principal(OTHER_DOCTOR_ID),
                request(CertificateType.DIAGNOSIS)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("의료법 제17조")
                .hasMessageContaining("김담당");
    }

    @Test
    @DisplayName("담당 의사 본인이면 진단서를 발급할 수 있다")
    void attendingDoctorCanIssueDiagnosis() {
        CertificateFacts facts = factsWithPrescription();
        when(assembler.loadFacts(1L)).thenReturn(facts);

        CertificateResponse issued = certificateService.issue(1L, principal(ATTENDING_DOCTOR_ID),
                request(CertificateType.DIAGNOSIS));

        assertThat(issued.issuerName()).isEqualTo("김담당");
        assertThat(issued.issuerLicense()).isEqualTo("12345");
    }

    @Test
    @DisplayName("진료(처방)가 확정되지 않으면 진단서를 발급할 수 없다")
    void cannotIssueDiagnosisWithoutPrescription() {
        CertificateFacts facts = factsWithoutPrescription();
        when(assembler.loadFacts(1L)).thenReturn(facts);

        assertThatThrownBy(() -> certificateService.issue(1L, principal(ATTENDING_DOCTOR_ID),
                request(CertificateType.DIAGNOSIS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진단서");
    }

    @Test
    @DisplayName("진료확인서는 병명이 없는 서류라 처방 없이도, 원무과도 발급할 수 있다")
    void staffCanIssueTreatmentConfirmationWithoutPrescription() {
        CertificateFacts facts = factsWithoutPrescription();
        when(assembler.loadFacts(1L)).thenReturn(facts);

        CertificateResponse issued = certificateService.issue(1L, principal(STAFF_ID),
                request(CertificateType.TREATMENT_CONFIRMATION));

        assertThat(issued.status()).isEqualTo(CertificateStatus.ISSUED);
        // 담당의를 특정할 수 없을 때만 요청자 이름으로 나간다.
        assertThat(issued.issuerName()).isEqualTo("이원무");
    }

    @Test
    @DisplayName("원무과가 처방전을 출력해도 서명은 처방한 의사 이름으로 나간다")
    void prescriptionIsSignedByAttendingDoctorEvenWhenStaffPrints() {
        CertificateFacts facts = factsWithPrescription();
        when(assembler.loadFacts(1L)).thenReturn(facts);

        CertificateResponse issued = certificateService.issue(1L, principal(STAFF_ID),
                request(CertificateType.PRESCRIPTION));

        assertThat(issued.issuerName()).isEqualTo("김담당");
        assertThat(issued.issuerLicense()).isEqualTo("12345");
    }

    @Test
    @DisplayName("발급번호는 저장 후 PK로 만든다 — 동시 발급에도 번호가 겹치지 않는다")
    void serialNoComesFromPrimaryKey() {
        CertificateFacts facts = factsWithPrescription();
        when(assembler.loadFacts(1L)).thenReturn(facts);

        CertificateResponse issued = certificateService.issue(1L, principal(ATTENDING_DOCTOR_ID),
                request(CertificateType.DIAGNOSIS));

        assertThat(issued.serialNo()).isEqualTo("2026-000042");
        // 스냅샷에도 같은 번호가 박혀야 재발급본이 원본과 일치한다.
        assertThat(issued.content().serialNo()).isEqualTo("2026-000042");
    }

    @Test
    @DisplayName("무효 처리된 증명서는 재발급할 수 없다")
    void cannotReissueVoidedCertificate() {
        Certificate voided = Certificate.builder()
                .id(7L).visitId(1L).patientId(2L)
                .type(CertificateType.DIAGNOSIS)
                .issuedBy(ATTENDING_DOCTOR_ID).issuerName("김담당")
                .content(emptyDocument())
                .status(CertificateStatus.ISSUED)
                .build();
        voided.voidCertificate("오발급");
        when(certificateRepository.findById(7L)).thenReturn(Optional.of(voided));

        assertThatThrownBy(() -> certificateService.reissue(7L, principal(ATTENDING_DOCTOR_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("무효");
    }

    @Test
    @DisplayName("재발급은 지금 데이터로 다시 만들지 않고 원본 스냅샷을 그대로 복사한다")
    void reissueCopiesOriginalSnapshot() {
        CertificateDocument original = emptyDocument().withNarrative(
                "발급 당시 소견", null, null, null, null);
        Certificate origin = Certificate.builder()
                .id(7L).visitId(1L).patientId(2L)
                .type(CertificateType.DIAGNOSIS)
                .issuedBy(ATTENDING_DOCTOR_ID).issuerName("김담당").issuerLicense("12345")
                .content(original)
                .status(CertificateStatus.ISSUED)
                .build();
        CertificateFacts facts = factsWithPrescription();
        when(certificateRepository.findById(7L)).thenReturn(Optional.of(origin));
        when(assembler.loadFacts(1L)).thenReturn(facts);

        CertificateResponse copy = certificateService.reissue(7L, principal(ATTENDING_DOCTOR_ID));

        assertThat(copy.content().opinion()).isEqualTo("발급 당시 소견");
        assertThat(copy.reissueOf()).isEqualTo(7L);
        // 재출력한 사람이 서명 주체가 되면 안 된다.
        assertThat(copy.issuerName()).isEqualTo("김담당");
    }

    /* ---------------------------------------------------------------- */

    private CertificateFacts factsWithPrescription() {
        Prescription prescription = mock(Prescription.class);
        when(prescription.getMemberId()).thenReturn(ATTENDING_DOCTOR_ID);
        return new CertificateFacts(visit(), patient(), prescription, attendingDoctor,
                List.of(), List.of());
    }

    private CertificateFacts factsWithoutPrescription() {
        return new CertificateFacts(visit(), patient(), null, null, List.of(), List.of());
    }

    private Visit visit() {
        Visit visit = mock(Visit.class);
        when(visit.getId()).thenReturn(1L);
        return visit;
    }

    private Patient patient() {
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(2L);
        return patient;
    }

    private Member member(Long id, String name, String license) {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getLicenseNumber()).thenReturn(license);
        return member;
    }

    private AuthPrincipal principal(Long memberId) {
        return new AuthPrincipal(memberId, "user" + memberId, "DOCTOR");
    }

    private CertificateIssueRequest request(CertificateType type) {
        return new CertificateIssueRequest(type, "보험 청구", "○○화재",
                null, null, null, null, null, null, null, null, null);
    }

    private CertificateDocument emptyDocument() {
        return new CertificateDocument(
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null,
                null, null,
                null, null, null, null, null, null, null,
                null, null);
    }
}
