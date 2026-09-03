package com.artifact.diagnosis.docshare;

import com.artifact.diagnosis.certificate.Certificate;
import com.artifact.diagnosis.certificate.CertificateDocument;
import com.artifact.diagnosis.certificate.CertificateRepository;
import com.artifact.diagnosis.certificate.CertificateType;
import com.artifact.diagnosis.patient.PatientService;
import com.artifact.diagnosis.prescription.PrescriptionService;
import com.artifact.diagnosis.visit.KioskTokenGenerator;
import com.artifact.diagnosis.visit.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 감열지 QR 로 들어온 사람에게 증명서를 언제 열어주는지 고정하는 테스트.
 *
 * 가장 중요한 것은 <b>첫 단계에서 내용이 나가지 않는다</b>는 사실이다. 생년월일을 화면에서만
 * 확인하고 내용은 미리 받아두면, 종이를 주운 사람이 개발자도구 Network 탭에서 진단명과 소견을
 * 그대로 읽는다. 잠금이 아니라 가림막일 뿐이다. 그 경계가 무너지지 않도록 여기서 못박는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentShareServiceTest {

    private static final String TOKEN = "abc123XYZ789";
    private static final String BIRTH_DATE = "1990-01-01";

    @Mock CertificateRepository certificateRepository;
    @Mock VisitRepository visitRepository;
    @Mock KioskTokenGenerator tokenGenerator;
    @Mock PatientService patientService;
    @Mock PrescriptionService prescriptionService;

    private DocumentShareService service;

    @BeforeEach
    void setUp() {
        // 가드는 진짜 객체를 쓴다. "5번 틀리면 잠긴다"가 서비스와 맞물려 도는지까지 봐야 한다.
        service = new DocumentShareService(certificateRepository, visitRepository, tokenGenerator,
                patientService, prescriptionService, new ShareAccessGuard());
        ReflectionTestUtils.setField(service, "ttlDays", 7);

        when(certificateRepository.findByShareToken(TOKEN)).thenReturn(Optional.of(certificate(BIRTH_DATE)));
    }

    // ── 1단계: 확인 화면을 열기 전 점검 ─────────────────────────────────────

    @Test
    @DisplayName("살아 있는 링크는 만료 시각만 돌려준다 — 이 단계에 문서 내용은 없다")
    void gateReturnsOnlyExpiry() {
        SharedDocumentGateResponse gate = service.openCertificateGate(TOKEN);

        assertThat(gate.expiresAt()).isNotNull();
        // 응답에 담긴 값이 만료 시각 하나뿐임을 타입 수준에서 확인한다.
        // 여기에 필드가 늘어나면 이 테스트를 고치면서 "그 값을 확인 전에 내보내도 되는가"를
        // 반드시 다시 판단하게 된다.
        assertThat(SharedDocumentGateResponse.class.getRecordComponents()).hasSize(1);
    }

    @Test
    @DisplayName("없는 토큰이면 404 — 생년월일을 묻기 전에 알려준다")
    void gateRejectsUnknownToken() {
        when(certificateRepository.findByShareToken("nope00000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openCertificateGate("nope00000000"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("기간이 지난 링크는 확인 화면도 열리지 않는다")
    void gateRejectsExpiredLink() {
        Certificate expired = certificate(BIRTH_DATE);
        expired.setShareTokenIssuedAt(LocalDateTime.now().minusDays(8));   // ttlDays=7
        when(certificateRepository.findByShareToken(TOKEN)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.openCertificateGate(TOKEN))
                .isInstanceOf(ShareLinkExpiredException.class);
    }

    // ── 2단계: 생년월일 확인 ────────────────────────────────────────────────

    @Test
    @DisplayName("생년월일이 맞으면 발급 당시 스냅샷을 그대로 돌려준다")
    void verifyOpensDocument() {
        SharedCertificateResponse response = service.verifyCertificate(TOKEN, "19900101");

        assertThat(response.content().patientBirthDate()).isEqualTo(BIRTH_DATE);
        assertThat(response.typeLabel()).isNotBlank();
    }

    @Test
    @DisplayName("하이픈·점을 넣어 쳐도 통과한다 — 형식을 외우게 하지 않는다")
    void verifyAcceptsAnySeparator() {
        assertThat(service.verifyCertificate(TOKEN, "1990-01-01")).isNotNull();
        assertThat(service.verifyCertificate(TOKEN, "1990.01.01")).isNotNull();
        assertThat(service.verifyCertificate(TOKEN, " 19900101 ")).isNotNull();
    }

    @Test
    @DisplayName("생년월일이 틀리면 남은 시도 횟수와 함께 거절한다")
    void verifyRejectsWrongBirthDate() {
        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                .isInstanceOf(ShareVerificationFailedException.class)
                .hasMessageContaining("남은 시도 4회");
    }

    @Test
    @DisplayName("반복해서 틀리면 잠긴다 — 생년월일은 경우의 수가 적어 이 방어가 없으면 뚫린다")
    void verifyLocksAfterRepeatedFailures() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                    .isInstanceOf(ShareVerificationFailedException.class);
        }
        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                .isInstanceOf(ShareVerificationLockedException.class);
    }

    @Test
    @DisplayName("잠긴 뒤에는 맞는 생년월일도 열리지 않는다")
    void lockedTokenRejectsEvenCorrectBirthDate() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                    .isInstanceOf(ShareVerificationFailedException.class);
        }
        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                .isInstanceOf(ShareVerificationLockedException.class);

        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900101"))
                .isInstanceOf(ShareVerificationLockedException.class);
    }

    @Test
    @DisplayName("한 번 성공하면 그 전의 실패는 따라붙지 않는다")
    void successClearsFailureHistory() {
        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                .isInstanceOf(ShareVerificationFailedException.class);
        service.verifyCertificate(TOKEN, "19900101");

        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900102"))
                .hasMessageContaining("남은 시도 4회");
    }

    @Test
    @DisplayName("스냅샷에 생년월일이 없으면 열어주지 않는다 — 확인할 수단이 없는 서류가 오히려 무방비가 된다")
    void verifyRefusesWhenSnapshotHasNoBirthDate() {
        when(certificateRepository.findByShareToken(TOKEN)).thenReturn(Optional.of(certificate(null)));

        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900101"))
                .isInstanceOf(ShareVerificationFailedException.class)
                .hasMessageContaining("병원에 문의");
    }

    @Test
    @DisplayName("기간이 지난 링크는 생년월일이 맞아도 열리지 않는다")
    void verifyRejectsExpiredLink() {
        Certificate expired = certificate(BIRTH_DATE);
        expired.setShareTokenIssuedAt(LocalDateTime.now().minusDays(8));
        when(certificateRepository.findByShareToken(TOKEN)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.verifyCertificate(TOKEN, "19900101"))
                .isInstanceOf(ShareLinkExpiredException.class);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Certificate certificate(String birthDate) {
        Certificate certificate = Certificate.builder()
                .id(1L)
                .type(CertificateType.DIAGNOSIS)
                .content(documentWithBirthDate(birthDate))
                .build();
        certificate.setShareToken(TOKEN);
        certificate.setShareTokenIssuedAt(LocalDateTime.now());
        return certificate;
    }

    private CertificateDocument documentWithBirthDate(String birthDate) {
        return new CertificateDocument(
                null, null, null, null, null,
                "홍길동", null, null, birthDate, null,
                null, null, null, null,
                null, null, null, null,
                null,
                null, null,
                null, null, null, null, null, null, null,
                null, null);
    }
}
