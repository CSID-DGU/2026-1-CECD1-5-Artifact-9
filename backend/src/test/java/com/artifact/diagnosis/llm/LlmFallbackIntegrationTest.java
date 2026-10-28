package com.artifact.diagnosis.llm;

import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.certificate.CertificateDocument;
import com.artifact.diagnosis.certificate.CertificateDraftRequest;
import com.artifact.diagnosis.certificate.CertificateDraftResponse;
import com.artifact.diagnosis.certificate.CertificateDraftService;
import com.artifact.diagnosis.certificate.CertificateFacts;
import com.artifact.diagnosis.certificate.CertificateType;
import com.artifact.diagnosis.common.config.DataInitializer;
import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.member.MemberResponse;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.member.MemberService;
import com.artifact.diagnosis.member.MemberSignupRequest;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.prescription.AiCommentService;
import com.artifact.diagnosis.prescription.Prescription;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Spring 배선으로 대체 체인을 끝까지 돌려 본다 — 처방 코멘트(약품 추천)·증명서 초안·키오스크 소견.
 *
 * Gemini 와 Claude 자리에 가짜 서버를 세우고 base-url 만 그쪽으로 돌린다. 그래서 설정값 해석,
 * 체인 조립 순서, 각 공급자의 요청·응답 처리, 컨트롤러 응답 모양까지 운영과 같은 코드가 돈다.
 * 실제 외부 API 는 부르지 않는다(키도 가짜다).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:artifact_llm_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "cloud.aws.credentials.access-key=test-access-key",
        "cloud.aws.credentials.secret-key=test-secret-key",
        "cloud.aws.s3.bucket=test-bucket",
        "image.storage.type=local",
        "jwt.secret=test-only-dummy-signing-key-not-used-anywhere-else-0123456789",
        "print.agent.enabled=false",
        "fastapi.internal-secret=test-only-internal-secret",

        // 체인: gemini-primary → gemini-backup → claude-opus-5-5
        "gemini.api.key=test-gemini-key",
        "gemini.model=gemini-primary",
        "gemini.fallback-models=gemini-backup",
        "gemini.max-attempts=1",
        "anthropic.api.key=test-claude-key",
        "anthropic.model=claude-opus-5-5",
        "anthropic.max-attempts=1",
        // 일시 장애 뒤 쉬게 하기를 끈다. 설정 오류(10분)는 이 값과 무관하므로 @BeforeEach 에서 표를 비운다.
        // 쉬게 하기 자체는 LlmGatewayTest 가 본다.
        "llm.cooldown-seconds=0"
})
class LlmFallbackIntegrationTest {

    private static final FakeLlmServer GEMINI = new FakeLlmServer();
    private static final FakeLlmServer CLAUDE = new FakeLlmServer();

    private static final String HIGH_DEMAND = FakeLlmServer.geminiError(503, "UNAVAILABLE",
            "This model is currently experiencing high demand. Spikes in demand are usually temporary. "
                    + "Please try again later.");
    private static final String COMMENT = "가 연고(A1)를 하루 2회 도포하는 것을 권장합니다.\n자외선 차단에 유의하세요.";

    @DynamicPropertySource
    static void llmServers(DynamicPropertyRegistry registry) {
        registry.add("llm.gemini-base-url", GEMINI::baseUrl);
        registry.add("llm.claude-base-url", CLAUDE::baseUrl);
    }

    /** 약품·상병 xlsx 수십만 행을 백그라운드로 적재하는 초기화는 이 테스트와 무관하다. */
    @MockitoBean
    DataInitializer dataInitializer;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberService memberService;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired JwtUtil jwtUtil;
    @Autowired AiCommentService aiCommentService;
    @Autowired CertificateDraftService certificateDraftService;
    @Autowired LlmGateway llmGateway;

    private String doctorToken;
    private Long visitId;

    @BeforeAll
    void createVisit() {
        MemberResponse doctor = memberService.signup(new MemberSignupRequest(
                "llm-doctor", "1234", "체인의사", "LIC-7001", "피부과", null));
        doctorToken = jwtUtil.generate(doctor.memberId(), "llm-doctor", MemberRole.DOCTOR.name());

        Patient patient = patientRepository.save(Patient.builder()
                .name("한체인").birthDate(LocalDate.of(1988, 3, 3)).gender(Gender.F).build());
        visitId = visitRepository.save(Visit.builder()
                .patientId(patient.getId())
                .visitDate(LocalDateTime.of(2026, 10, 28, 10, 0))
                .receptionMemo("한체인 님 010-4321-8765 가려움")
                .status(VisitStatus.DIAGNOSED)
                .build()).getId();
    }

    @AfterAll
    void stopServers() {
        GEMINI.close();
        CLAUDE.close();
    }

    @BeforeEach
    void resetServers() {
        GEMINI.reset();
        CLAUDE.reset();
        // 한 Spring 컨텍스트의 게이트웨이를 모든 테스트가 나눠 쓴다. 앞 테스트의 쉬기가 넘어오지 않게 한다.
        llmGateway.clearRests();
    }

    @Test
    @DisplayName("처방 코멘트: 1차 Gemini 가 503 이면 대체 Gemini 모델의 답과 그 모델명을 돌려준다")
    void commentFallsBackToSecondGeminiModel() throws Exception {
        // 실제로 답한 모델(modelVersion)이 처방 기록까지 그대로 가야 한다 — 별칭·설정값이 아니다.
        GEMINI.on("gemini-primary", 503, HIGH_DEMAND)
              .on("gemini-backup", 200, FakeLlmServer.geminiText(COMMENT, "gemini-3.5-flash-lite-001"));

        JsonNode body = requestComment();

        assertThat(body.get("available").isBoolean()).isTrue();
        assertThat(body.get("available").booleanValue()).isTrue();
        assertThat(body.path("model").asText()).isEqualTo("gemini-3.5-flash-lite-001");
        assertThat(body.path("line1").asText()).isEqualTo("가 연고(A1)를 하루 2회 도포하는 것을 권장합니다.");
        assertThat(body.path("line2").asText()).isEqualTo("자외선 차단에 유의하세요.");
        assertThat(GEMINI.receivedFor("gemini-primary")).hasSize(1);
        assertThat(GEMINI.receivedFor("gemini-backup")).hasSize(1);
        assertThat(CLAUDE.received()).isEmpty();
    }

    @Test
    @DisplayName("처방 코멘트: Gemini 가 모두 503 이면 Claude 가 답하고, Claude 에도 마스킹된 메모만 나간다")
    void commentFallsBackToClaude() throws Exception {
        GEMINI.on("gemini-primary", 503, HIGH_DEMAND)
              .on("gemini-backup", 503, HIGH_DEMAND);
        CLAUDE.on("/v1/messages", 200, FakeLlmServer.claudeText("claude-opus-5-5", COMMENT));

        JsonNode body = requestComment();

        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("line1").asText()).startsWith("가 연고(A1)");

        FakeLlmServer.Received sent = CLAUDE.received().get(0);
        JsonNode request = objectMapper.readTree(sent.body());
        assertThat(request.path("fallbacks").asText()).isEqualTo("default");
        assertThat(sent.header("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        // 공급자가 늘어도 개인정보 경계는 그대로여야 한다 — 전화번호·이름이 Anthropic 으로 나가면 안 된다.
        String prompt = request.at("/messages/0/content").toString();
        assertThat(prompt).contains("가려움").doesNotContain("010-4321-8765").doesNotContain("한체인");
    }

    @Test
    @DisplayName("처방 코멘트: 전부 실패하면 available=false 와 '혼잡' 안내를 주고, 모델명은 비운다")
    void commentReportsCongestionWhenEveryProviderFails() throws Exception {
        GEMINI.on("gemini-primary", 503, HIGH_DEMAND)
              .on("gemini-backup", 503, HIGH_DEMAND);
        CLAUDE.on("/v1/messages", 529, FakeLlmServer.claudeError("overloaded_error", "Overloaded"));

        JsonNode body = requestComment();

        // 필드가 빠져도 asBoolean() 은 false 다. 화면은 available === false 일 때만 실패로 보므로, 값이 실제로
        // false 로 내려오는지 본다 — 빠지면 실패 문구가 코멘트로 저장된다.
        assertThat(body.has("available")).isTrue();
        assertThat(body.get("available").isBoolean()).isTrue();
        assertThat(body.get("available").booleanValue()).isFalse();
        assertThat(body.path("line1").asText()).contains("혼잡");
        // 실패 안내가 코멘트처럼 저장되지 않도록, 프론트가 모델명 없이 받는다.
        assertThat(body.hasNonNull("model")).isFalse();
        assertThat(GEMINI.received()).hasSize(2);
        assertThat(CLAUDE.received()).hasSize(1);
    }

    @Test
    @DisplayName("증명서 초안: Claude 로 넘어가도 서술 칸을 JSON 스키마로 받아 채운다")
    void certificateDraftFallsBackToClaudeWithStructuredOutput() throws Exception {
        GEMINI.on("gemini-primary", 503, HIGH_DEMAND)
              .on("gemini-backup", 503, HIGH_DEMAND);
        CLAUDE.on("/v1/messages", 200, FakeLlmServer.claudeText("claude-opus-5-5",
                "{\"opinion\":\"아토피피부염 소견입니다.\",\"treatmentPlan\":\"보습과 국소 스테로이드 도포\"}"));

        CertificateDraftResponse response = certificateDraftService.draft(certificateFacts(),
                new CertificateDraftRequest(CertificateType.DIAGNOSIS, "보험 청구", "○○화재", null));

        assertThat(response.generated()).isTrue();
        assertThat(response.model()).isEqualTo("claude-opus-5-5");
        assertThat(response.opinion()).isEqualTo("아토피피부염 소견입니다.");
        assertThat(response.treatmentPlan()).isEqualTo("보습과 국소 스테로이드 도포");

        // Gemini 에는 temperature 0.2 가 가고, Claude 에는 가지 않는다(최신 모델은 받으면 400).
        JsonNode geminiRequest = objectMapper.readTree(GEMINI.receivedFor("gemini-primary").get(0).body());
        assertThat(geminiRequest.at("/generationConfig/temperature").asDouble()).isEqualTo(0.2);
        JsonNode claudeRequest = objectMapper.readTree(CLAUDE.received().get(0).body());
        assertThat(claudeRequest.has("temperature")).isFalse();
        assertThat(claudeRequest.at("/output_config/format/schema/required").toString())
                .isEqualTo("[\"opinion\",\"treatmentPlan\"]");

        // 접수 메모·의사 메모는 자유 서술이라 이름·전화번호가 섞인다. 어느 공급자로 가든 지운 뒤에 나가야 한다.
        String geminiPrompt = geminiRequest.at("/contents/0/parts/0/text").asText();
        String claudePrompt = claudeRequest.at("/messages/0/content").toString();
        for (String prompt : List.of(geminiPrompt, claudePrompt)) {
            assertThat(prompt).contains("가려움").contains("보습")
                    .doesNotContain("010-1234-5678").doesNotContain("010-9876-5432").doesNotContain("홍길동");
        }
    }

    @Test
    @DisplayName("증명서 초안: Gemini 가 JSON 이 아닌 답을 주면 성공으로 치지 않고 다음 공급자로 넘어간다")
    void certificateDraftSkipsNonJsonAnswer() {
        GEMINI.on("gemini-primary", 200, FakeLlmServer.geminiText("소견: 아토피피부염으로 보입니다."))
              .on("gemini-backup", 200, FakeLlmServer.geminiText("{\"opinion\":\"대체 모델 소견\",\"treatmentPlan\":\"보습\"}"));

        CertificateDraftResponse response = certificateDraftService.draft(certificateFacts(),
                new CertificateDraftRequest(CertificateType.DIAGNOSIS, "보험 청구", "○○화재", null));

        assertThat(response.generated()).isTrue();
        assertThat(response.model()).isEqualTo("gemini-backup");
        assertThat(response.opinion()).isEqualTo("대체 모델 소견");
        assertThat(CLAUDE.received()).isEmpty();
    }

    @Test
    @DisplayName("키오스크 소견: 1차가 503 이어도 대체 모델의 소견을 보여 준다")
    void kioskCommentFallsBack() {
        GEMINI.on("gemini-primary", 503, HIGH_DEMAND)
              .on("gemini-backup", 200, FakeLlmServer.geminiText(
                      "촬영하신 부위는 여드름 가능성이 있는 것으로 보입니다.\n정확한 판단을 위해 의사의 확인 진료가 필요합니다."));

        String comment = aiCommentService.generatePreliminaryComment(List.of(new TopKItem("acne", 0.82)));

        assertThat(comment).startsWith("촬영하신 부위는 여드름");
        assertThat(CLAUDE.received()).isEmpty();
    }

    private JsonNode requestComment() throws Exception {
        String response = mockMvc.perform(post("/api/v1/visits/{visitId}/prescription/comment", visitId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"diseases": [{"kcdCode": "L20", "kcdNameKr": "아토피피부염", "isPrimary": true}]}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private static CertificateFacts certificateFacts() {
        Patient patient = Patient.builder()
                .name("홍길동").birthDate(LocalDate.of(1990, 1, 1)).gender(Gender.M).build();
        Visit visit = Visit.builder()
                .patientId(1L)
                .visitDate(LocalDateTime.of(2026, 10, 28, 9, 30))
                .receptionMemo("홍길동 님 010-1234-5678 가려움이 심함")
                .status(VisitStatus.DIAGNOSED)
                .build();
        Prescription prescription = Prescription.builder()
                .visitId(1L)
                .memberId(1L)
                .doctorNotes("보습 강조. 보호자 연락처 010-9876-5432")
                .build();
        return new CertificateFacts(visit, patient, prescription, null,
                List.of(new CertificateDocument.DiseaseLine("L20.9", "아토피피부염", true)),
                List.of());
    }
}
