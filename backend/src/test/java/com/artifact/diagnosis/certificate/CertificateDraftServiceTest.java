package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.visit.Visit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 초안이 실패해도 발급은 계속돼야 한다는 것을 고정하는 테스트.
 *
 * 초안은 의사의 타자를 대신 쳐주는 편의 기능일 뿐이다. Gemini 키가 없거나, 응답이 늦거나,
 * 외부 서버가 죽어도 예외가 위로 올라가면 안 된다 — 그러면 진단서를 손으로 써서 떼어줄 수도 없게 된다.
 * 모든 실패 경로가 {@code generated=false} 와 사람이 읽을 사유로 내려오는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateDraftServiceTest {

    @Mock HttpClient httpClient;

    CertificateDraftService draftService;

    @BeforeEach
    void setUp() {
        draftService = new CertificateDraftService(new ObjectMapper(), httpClient);
        ReflectionTestUtils.setField(draftService, "apiKey", "test-key");
        ReflectionTestUtils.setField(draftService, "timeoutSeconds", 15L);
        ReflectionTestUtils.setField(draftService, "model", "gemini-3.1-flash-lite");
    }

    @Test
    @DisplayName("Gemini 키가 없으면 예외 대신 수기 작성 안내를 돌려준다")
    void withoutApiKeyFallsBackToManualAuthoring() {
        ReflectionTestUtils.setField(draftService, "apiKey", "");

        CertificateDraftResponse response = draftService.draft(facts(), request(CertificateType.DIAGNOSIS));

        assertThat(response.generated()).isFalse();
        assertThat(response.message()).contains("직접 작성");
        assertThat(response.opinion()).isNull();
    }

    @Test
    @DisplayName("타임아웃이 나도 예외가 위로 올라가지 않는다")
    void timeoutDoesNotPropagate() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new HttpTimeoutException("request timed out"));

        CertificateDraftResponse response = draftService.draft(facts(), request(CertificateType.DIAGNOSIS));

        assertThat(response.generated()).isFalse();
        assertThat(response.message()).contains("직접 작성");
    }

    @Test
    @DisplayName("외부 서버가 무엇을 던지든 발급 화면은 막히지 않는다")
    void neverThrows() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new RuntimeException("connection reset"));

        assertThatCode(() -> draftService.draft(facts(), request(CertificateType.MEDICAL_OPINION)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Gemini가 에러를 반환해도 사유를 안내로 바꿔 내려준다")
    void apiErrorBecomesGuidance() throws Exception {
        stubResponse("{\"error\":{\"code\":503,\"message\":\"model overloaded\"}}");

        CertificateDraftResponse response = draftService.draft(facts(), request(CertificateType.DIAGNOSIS));

        assertThat(response.generated()).isFalse();
        assertThat(response.message()).contains("혼잡");
    }

    @Test
    @DisplayName("빈 응답도 실패로 다루고 초안 없이 진행하게 한다")
    void emptyResponseIsHandled() throws Exception {
        stubResponse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}");

        CertificateDraftResponse response = draftService.draft(facts(), request(CertificateType.DIAGNOSIS));

        assertThat(response.generated()).isFalse();
    }

    @Test
    @DisplayName("서술 항목이 없는 처방전은 애초에 외부 호출을 하지 않는다")
    void prescriptionNeedsNoDraft() throws Exception {
        CertificateDraftResponse response = draftService.draft(facts(), request(CertificateType.PRESCRIPTION));

        assertThat(response.generated()).isFalse();
        assertThat(response.message()).contains("처방전");
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    @DisplayName("정상 응답은 서술 칸만 채워서 내려오고, 검토 안내가 함께 붙는다")
    void successReturnsNarrativeOnly() throws Exception {
        stubResponse("""
                {"candidates":[{"content":{"parts":[{"text":
                "{\\"opinion\\":\\"상기 환자는 경과 관찰이 필요함.\\",\\"treatmentPlan\\":\\"외용제 유지 후 재진 요함.\\"}"
                }]}}]}""");

        CertificateDraftResponse response = draftService.draft(facts(), request(CertificateType.DIAGNOSIS));

        assertThat(response.generated()).isTrue();
        assertThat(response.opinion()).isEqualTo("상기 환자는 경과 관찰이 필요함.");
        assertThat(response.treatmentPlan()).isEqualTo("외용제 유지 후 재진 요함.");
        assertThat(response.model()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(response.message()).isEqualTo(CertificateDraftResponse.DISCLAIMER);
    }

    /* ---------------------------------------------------------------- */

    @SuppressWarnings("unchecked")
    private void stubResponse(String body) throws Exception {
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(), any())).thenReturn(response);
    }

    private CertificateFacts facts() {
        Patient patient = mock(Patient.class);
        when(patient.getName()).thenReturn("홍길동");
        when(patient.getBirthDate()).thenReturn(LocalDate.of(1990, 1, 1));
        when(patient.getGender()).thenReturn(Gender.M);

        Visit visit = mock(Visit.class);
        when(visit.getVisitDate()).thenReturn(LocalDateTime.of(2026, 8, 28, 9, 30));
        when(visit.getReceptionMemo()).thenReturn(null);

        return new CertificateFacts(visit, patient, null, null,
                List.of(new CertificateDocument.DiseaseLine("L20.9", "아토피피부염", true)),
                List.of());
    }

    private CertificateDraftRequest request(CertificateType type) {
        return new CertificateDraftRequest(type, "보험 청구", "○○화재", null);
    }
}
