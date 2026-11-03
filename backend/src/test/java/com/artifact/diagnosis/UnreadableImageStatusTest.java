package com.artifact.diagnosis;

import com.artifact.diagnosis.analysis.AnalysisResultRepository;
import com.artifact.diagnosis.analysis.InvalidImageException;
import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.image.ImageStorageService;
import com.artifact.diagnosis.kiosk.PreliminaryAnalysisRepository;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitImage;
import com.artifact.diagnosis.visit.VisitImageRepository;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 서버가 사진을 열지 못했을 때 태블릿(키오스크 예비분석)과 진료실(분석)이 500 이 아니라 400 과
 * "다시 촬영해 달라"는 안내를 받는지. 가짜 FastAPI 가 정해 둔 상태 코드와 본문을 돌려준다
 * (KioskHeatmapCropTest 와 같은 방식).
 *
 * FastAPI 의 400 은 두 종류다 — 사진을 열 수 없다(detail.code = invalid_image)와, 백엔드가 요청을 잘못
 * 만들었다(깨진 base64·모르는 태스크 이름). 앞의 것만 화면에 400 으로 가고 뒤의 것은 500 으로 남는지 함께 고정한다
 * (이유는 FastApiErrors 주석). 실패한 뒤에 남는 것도 본다 — 예비분석 행은 생기지 않고, 진료실 접수는 분석 전 상태로
 * 돌아와 다른 사진으로 다시 분석할 수 있어야 한다.
 *
 * HTTP 로 부른다(MockMvc). 화면이 받는 것은 서비스의 예외가 아니라 GlobalExceptionHandler 를 거친 상태 코드와 본문이다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest
class UnreadableImageStatusTest {

    /** fastapi/main.py _open_image 가 사진을 열지 못했을 때 내는 본문 모양 그대로. */
    private static final String UNREADABLE_PHOTO = """
            {"detail":{"code":"invalid_image",
                       "message":"사진으로 알아볼 수 없는 파일입니다(3바이트). 이미지가 아니거나 이 서버가 읽지 못하는 형식(예: HEIC)입니다."}}
            """;
    /** fastapi/main.py predict_base64 가 깨진 base64 에 내는 본문. 400 이지만 사진 탓이 아니다. */
    private static final String BROKEN_BASE64 = """
            {"detail":"image_base64 가 올바른 base64 문자열이 아닙니다: Incorrect padding"}
            """;
    /** fastapi/main.py get_task_model 이 모르는 태스크 이름에 내는 본문. 400 이지만 사진 탓이 아니다. */
    private static final String UNKNOWN_TASK = """
            {"detail":"알 수 없는 분석 태스크입니다: 'skin_lesoin' (가능한 값: skin_lesion, facial)"}
            """;
    /** fastapi/main.py get_task_model 이 가중치 없는 태스크에 내는 본문. */
    private static final String MODEL_NOT_LOADED = """
            {"detail":"분석 태스크 'skin_lesion' 의 모델이 로드되지 않았습니다 (가중치 파일: model.pth). 사유: 파일 없음"}
            """;

    private static HttpServer fakeFastApi;
    private static final AtomicInteger STATUS = new AtomicInteger();
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger TOKEN_SEQ = new AtomicInteger();

    @BeforeAll
    static void startFakeFastApi() throws IOException {
        fakeFastApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFastApi.createContext("/predict-base64", exchange -> {
            exchange.getRequestBody().readAllBytes();
            REQUESTS.incrementAndGet();
            byte[] body = BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(STATUS.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fakeFastApi.start();
    }

    @AfterAll
    static void stopFakeFastApi() {
        if (fakeFastApi != null) fakeFastApi.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fastapi.url", () -> "http://127.0.0.1:" + fakeFastApi.getAddress().getPort());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:artifact_unreadable_image;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // AnalysisResilienceTest 와 같은 이유 — 마이그레이션 SQL 은 MySQL 방언이라 스키마는 엔티티에서 만든다.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("cloud.aws.credentials.access-key", () -> "test-access-key");
        registry.add("cloud.aws.credentials.secret-key", () -> "test-secret-key");
        registry.add("cloud.aws.s3.bucket", () -> "test-bucket");
        registry.add("image.storage.type", () -> "local");
        registry.add("jwt.secret", () -> "test-only-dummy-signing-key-not-used-anywhere-else-0123456789");
        registry.add("fastapi.internal-secret", () -> "test-only-internal-secret");
        // 개발자 셸이나 backend/.env 에 실제 LLM 키가 있어도 테스트가 유료 외부 호출을 하지 않게 비워 둔다.
        registry.add("gemini.api.key", () -> "");
        registry.add("anthropic.api.key", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired VisitImageRepository visitImageRepository;
    @Autowired ImageStorageService imageStorageService;
    @Autowired PreliminaryAnalysisRepository preliminaryAnalysisRepository;
    @Autowired AnalysisResultRepository analysisResultRepository;

    /** 태블릿이 사진을 올렸는데 AI 서버가 열지 못함 → 400 과 다시 찍으라는 안내. 예비분석 행은 생기지 않는다. */
    @Test
    void tabletGetsBadRequestAndNoPreliminaryRowWhenThePhotoCannotBeOpened() throws Exception {
        Visit visit = receivedVisit();

        kioskAnalyze(visit, UNREADABLE_PHOTO, 400)
                .andExpect(errorBody(400))
                // FastAPI 가 적어 준 사유(HEIC, 몇 바이트 …)는 화면에 싣지 않는다 — 환자가 읽는 문장이다.
                .andExpect(jsonPath("$.message").value(InvalidImageException.MESSAGE));

        assertThat(preliminaryAnalysisRepository.existsByVisitId(visit.getId())).isFalse();
    }

    /** 깨진 base64 는 FastAPI 에서 400 이지만 백엔드가 만든 요청이 틀린 것이다 — 태블릿에 "다시 촬영"을 띄우지 않는다. */
    @Test
    void tabletGetsServerErrorWhenFastApiRejectsTheRequestItself() throws Exception {
        Visit visit = receivedVisit();

        kioskAnalyze(visit, BROKEN_BASE64, 400).andExpect(errorBody(500));

        assertThat(preliminaryAnalysisRepository.existsByVisitId(visit.getId())).isFalse();
    }

    /** 모델 미준비(503)는 진료실처럼 태블릿에도 503 이다. 전에는 키오스크만 500 이었다. */
    @Test
    void tabletGetsServiceUnavailableWhenTheModelIsNotLoaded() throws Exception {
        Visit visit = receivedVisit();

        kioskAnalyze(visit, MODEL_NOT_LOADED, 503).andExpect(errorBody(503));

        assertThat(preliminaryAnalysisRepository.existsByVisitId(visit.getId())).isFalse();
    }

    /**
     * 진료실에서도 같은 사진 문제는 400 이다. 분석 결과는 저장되지 않고, 접수는 ANALYZING 에 갇히지 않고
     * 분석 전 상태(IN_PROGRESS)로 돌아온다 — 그래야 다른 사진을 올려 바로 다시 분석할 수 있다.
     */
    @Test
    void clinicGetsBadRequestAndKeepsTheVisitAnalyzableWhenThePhotoCannotBeOpened() throws Exception {
        Visit visit = inProgressVisit();

        clinicAnalyze(visit, UNREADABLE_PHOTO, 400)
                .andExpect(errorBody(400))
                .andExpect(jsonPath("$.message").value(InvalidImageException.MESSAGE));

        assertThat(visitRepository.findById(visit.getId()).orElseThrow().getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(analysisResultRepository.findFirstByVisitIdOrderByAnalyzedAtDesc(visit.getId())).isEmpty();
    }

    /**
     * 모르는 태스크 이름의 400 은 백엔드 버그(AnalysisType 과 FastAPI TASKS 가 어긋남)라 500 으로 남는다.
     * 400 으로 넘기면 의사는 몇 번을 다시 찍어도 같은 안내를 본다. 접수 상태 복구는 같다.
     */
    @Test
    void clinicGetsServerErrorWhenFastApiDoesNotKnowTheTask() throws Exception {
        Visit visit = inProgressVisit();

        clinicAnalyze(visit, UNKNOWN_TASK, 400).andExpect(errorBody(500));

        assertThat(visitRepository.findById(visit.getId()).orElseThrow().getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(analysisResultRepository.findFirstByVisitIdOrderByAnalyzedAtDesc(visit.getId())).isEmpty();
    }

    /** 태블릿 분석 요청. 가짜 FastAPI 가 status·body 로 답하게 하고, 요청이 실제로 FastAPI 까지 갔는지 함께 확인한다. */
    private ResultActions kioskAnalyze(Visit visit, String fastApiBody, int fastApiStatus) throws Exception {
        fastApiAnswers(fastApiStatus, fastApiBody);
        int before = REQUESTS.get();
        // 사진 바이트는 아무것이어도 된다 — 열 수 있는지는 가짜 FastAPI 의 답이 정한다.
        MockMultipartFile photo = new MockMultipartFile("file", "lesion.heic", "image/heic", new byte[]{1, 2, 3});
        ResultActions result = mockMvc.perform(multipart("/api/kiosk/session/{token}/analyze", visit.getKioskToken())
                .file(photo));
        // FastAPI 에 닿기 전에 다른 이유(토큰·파트 이름)로 4xx 가 난 것이 아님을 확인한다.
        assertThat(REQUESTS.get()).isEqualTo(before + 1);
        return result;
    }

    /** 진료실 분석 요청(간호사 이상). kioskAnalyze 와 같은 확인을 한다. */
    private ResultActions clinicAnalyze(Visit visit, String fastApiBody, int fastApiStatus) throws Exception {
        fastApiAnswers(fastApiStatus, fastApiBody);
        int before = REQUESTS.get();
        ResultActions result = mockMvc.perform(post("/api/v1/visits/{visitId}/analysis", visit.getId())
                .header("Authorization", "Bearer " + jwtUtil.generate(1L, "unreadable-image-doctor", MemberRole.DOCTOR.name()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageIds\":[" + imageOf(visit) + "]}"));
        assertThat(REQUESTS.get()).isEqualTo(before + 1);
        return result;
    }

    private static void fastApiAnswers(int status, String body) {
        STATUS.set(status);
        BODY.set(body);
    }

    /** GlobalExceptionHandler 가 만드는 공통 오류 본문 {timestamp, status, message} 와 그 상태 코드(ClientErrorStatusTest 와 같다). */
    private static ResultMatcher errorBody(int status) {
        return ResultMatcher.matchAll(
                status().is(status),
                jsonPath("$.status").value(status),
                jsonPath("$.message").isNotEmpty(),
                jsonPath("$.timestamp").exists());
    }

    private Patient savePatient() {
        return patientRepository.save(Patient.builder()
                .name("사진환자").birthDate(LocalDate.of(1991, 3, 3)).gender(Gender.F).build());
    }

    /** 접수만 하고 진료 전인 태블릿 환자. 토큰은 실제 발급 토큰처럼 12자다(CHAR(12)). */
    private Visit receivedVisit() {
        return visitRepository.save(Visit.builder()
                .patientId(savePatient().getId())
                .visitDate(LocalDateTime.now())
                .status(VisitStatus.RECEIVED)
                .kioskToken("UNREADABLE%02d".formatted(TOKEN_SEQ.incrementAndGet()))
                .build());
    }

    /** 진료 중이고 사진이 한 장 올라와 있는 진료실 환자. 분석은 스토리지에서 원본을 읽으므로 파일 자체는 있어야 한다. */
    private Visit inProgressVisit() {
        Visit visit = visitRepository.save(Visit.builder()
                .patientId(savePatient().getId())
                .visitDate(LocalDateTime.now())
                .status(VisitStatus.IN_PROGRESS)
                .build());
        visitImageRepository.save(VisitImage.builder()
                .visitId(visit.getId())
                .imageUrl(imageStorageService.uploadBytes("test/unreadable-" + visit.getId() + ".heic",
                        new byte[]{1, 2, 3}, "image/heic"))
                .uploadedAt(LocalDateTime.now())
                .build());
        return visit;
    }

    private Long imageOf(Visit visit) {
        return visitImageRepository.findAll().stream()
                .filter(i -> i.getVisitId().equals(visit.getId()))
                .findFirst().orElseThrow().getId();
    }
}
