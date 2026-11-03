package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.AiServiceUnavailableException;
import com.artifact.diagnosis.analysis.AnalysisType;
import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 부스 운영(2026-11-16~)의 키오스크 흐름 — 여러 기기가 동시에 찍어도 결과가 섞이지 않고, 방문자가 고른 모델로
 * 분석되고, 그 결과가 의사 화면까지 그대로 이어지는지.
 *
 * 가짜 FastAPI 는 **받은 사진의 내용으로** 답을 만든다. 사진 바이트가 "photo-3" 이면 1순위 코드와 히트맵 바이트에
 * 3 이 들어간다. 그래서 응답·저장된 행·히트맵이 다른 요청의 것으로 바뀌면 내용으로 드러난다 — 모든 요청에 같은
 * 답을 주면 섞여도 알아볼 수 없다.
 *
 * 동시 요청을 실제로 겹치게 하려고 가짜 서버에 스레드 풀을 주고, 필요한 테스트는 응답을 래치로 붙잡아 둔다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest
class KioskBoothFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FACIAL = List.of("acne", "atopic", "normal", "psoriasis", "rosacea", "seb_derm");
    private static final List<String> SKIN = List.of("akiec", "bcc", "bkl", "df", "mel", "nv", "vasc", "inflammatory");

    private static HttpServer fakeFastApi;
    private static final AtomicReference<String> LAST_TASK = new AtomicReference<>();
    /** 이 값이 있으면 요청이 들어왔다고 알리고(arrived) 풀릴 때(release)까지 응답을 붙잡는다. */
    private static final AtomicReference<CountDownLatch[]> HOLD = new AtomicReference<>();
    /** 이 값이 있으면 요청과 다른 task 로 답한다 — 구버전 FastAPI·잘못된 배포를 흉내 낸다. */
    private static final AtomicReference<String> SERVE_TASK_OVERRIDE = new AtomicReference<>();
    private static final AtomicInteger TOKEN_SEQ = new AtomicInteger();

    @BeforeAll
    static void startFakeFastApi() throws IOException {
        fakeFastApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFastApi.setExecutor(Executors.newFixedThreadPool(8));
        fakeFastApi.createContext("/predict-base64", exchange -> {
            JsonNode req = JSON.readTree(exchange.getRequestBody());
            String task = req.path("task").asText("skin_lesion");
            LAST_TASK.set(task);
            String photo = new String(Base64.getDecoder().decode(req.path("image_base64").asText()), StandardCharsets.UTF_8);
            CountDownLatch[] hold = HOLD.get();
            if (hold != null) {
                hold[0].countDown();
                try {
                    hold[1].await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = answer(photo, SERVE_TASK_OVERRIDE.get() != null ? SERVE_TASK_OVERRIDE.get() : task)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
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

    /** 사진 "photo-n" → n 번째 클래스가 1순위, 히트맵 바이트는 "heat-photo-n". */
    private static String answer(String photo, String task) {
        List<String> classes = "facial".equals(task) ? FACIAL : SKIN;
        int n = Integer.parseInt(photo.substring(photo.lastIndexOf('-') + 1));
        String code = classes.get(n % classes.size());
        return """
                {"confidence_level":"normal","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"%1$s","disease_name_ko":"%1$s","confidence":0.8},
                 "top5":[{"rank":1,"disease_code":"%1$s","disease_name_ko":"%1$s","confidence":0.8}],
                 "heatmap_base64":"%2$s","model_version":"efficientnet_b0-test","task":"%3$s",
                 "heatmap_style":"layercam-flip-jet-v2","crops":[]}
                """.formatted(code, b64(heatmapOf(photo)), task);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fastapi.url", () -> "http://127.0.0.1:" + fakeFastApi.getAddress().getPort());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:artifact_kiosk_booth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
        // 참고 소견 테스트가 유료 LLM 을 부르지 않게 비워 둔다 — 키가 없으면 안내 문구가 바로 돌아온다.
        registry.add("gemini.api.key", () -> "");
        registry.add("anthropic.api.key", () -> "");
    }

    @Autowired KioskService kioskService;
    @Autowired KioskTransactionService kioskTransactionService;
    @Autowired PreliminaryAnalysisRepository preliminaryAnalysisRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;

    @BeforeEach
    void reset() {
        HOLD.set(null);
        SERVE_TASK_OVERRIDE.set(null);
    }

    /** 방문자가 '얼굴 피부'를 고르면 안면부 모델로 분석되고, 의사 화면도 그 모델의 결과라고 안다. */
    @Test
    void visitorChoiceReachesFastApiAndTheDoctorScreen() throws Exception {
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();

        mockMvc.perform(multipart("/api/kiosk/session/{token}/analyze", token)
                        .file(photo(2)).param("analysisType", "facial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisType").value("facial"))
                .andExpect(jsonPath("$.topK[0].diseaseCode").value(FACIAL.get(2)))
                // 소견은 분석 응답에 없다 — 결과를 먼저 그리고 따로 받는다.
                .andExpect(jsonPath("$.aiComment").value(nullValue()));
        assertThat(LAST_TASK.get()).isEqualTo("facial");

        mockMvc.perform(get("/api/v1/visits/{visitId}/preliminary", visit.getId())
                        .header("Authorization", "Bearer " + tokenFor(MemberRole.DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisType").value("facial"))
                .andExpect(jsonPath("$.topK[0].diseaseCode").value(FACIAL.get(2)))
                .andExpect(jsonPath("$.topK[0].diseaseNameKo").value("정상 피부"));

        // 모델을 고르지 않은 요청(이 필드가 생기기 전의 화면)은 전처럼 피부 병변 모델이다.
        mockMvc.perform(multipart("/api/kiosk/session/{token}/analyze", token).file(photo(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisType").value("skin_lesion"));
        assertThat(LAST_TASK.get()).isEqualTo("skin_lesion");
    }

    @Test
    void unknownModelChoiceIsRejectedBeforeAnalysis() throws Exception {
        Visit visit = receivedVisit();
        mockMvc.perform(multipart("/api/kiosk/session/{token}/analyze", visit.getKioskToken())
                        .file(photo(1)).param("analysisType", "burn"))
                .andExpect(status().isBadRequest());
        assertThat(preliminaryAnalysisRepository.findByVisitId(visit.getId())).isEmpty();
    }

    /**
     * 얼굴을 골랐는데 FastAPI 가 피부 병변 모델로 답하면(가중치 미배포·구버전) 저장하지 않는다.
     * 저장하면 top_k 의 코드가 다른 클래스 목록을 가리키는 거짓 기록이 된다.
     */
    @Test
    void answerFromAnotherModelIsNotSaved() {
        Visit visit = receivedVisit();
        SERVE_TASK_OVERRIDE.set("skin_lesion");

        assertThatThrownBy(() -> kioskService.analyze(visit.getKioskToken(), photo(1), AnalysisType.FACIAL))
                .isInstanceOf(AiServiceUnavailableException.class);
        assertThat(preliminaryAnalysisRepository.findByVisitId(visit.getId())).isEmpty();
    }

    /**
     * 같은 접수를 두 화면이 동시에 분석하면 하나만 진행하고 다른 하나는 409 다.
     * 겹치게 두면 첫 저장이 UNIQUE 에 걸려 500 이 나거나, 먼저 끝난 화면이 다른 사진의 히트맵을 받는다.
     */
    @Test
    void sameVisitAnalyzedTwiceAtOnceGetsConflict() throws Exception {
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HOLD.set(new CountDownLatch[]{arrived, release});

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PreliminaryAnalysisResponse> first =
                    pool.submit(() -> kioskService.analyze(token, photo(3), AnalysisType.FACIAL));
            assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue(); // 첫 요청이 FastAPI 에 닿아 붙잡혀 있다

            mockMvc.perform(multipart("/api/kiosk/session/{token}/analyze", token)
                            .file(photo(5)).param("analysisType", "facial"))
                    .andExpect(status().isConflict());

            HOLD.set(null);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).topK().get(0).diseaseCode()).isEqualTo(FACIAL.get(3));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // 첫 요청이 끝나면 다시 분석할 수 있다 — 막힌 채로 남지 않는다.
        assertThat(kioskService.analyze(token, photo(5), AnalysisType.FACIAL).topK().get(0).diseaseCode())
                .isEqualTo(FACIAL.get(5));
    }

    /**
     * 부스 기기 여러 대가 서로 다른 접수를 동시에 분석해도 결과·저장된 행·히트맵이 각자의 사진 것이다.
     * 사진마다 답이 다르므로 하나라도 섞이면 내용이 어긋난다.
     */
    @Test
    void concurrentVisitsKeepTheirOwnResults() throws Exception {
        int devices = 6;
        List<Visit> visits = new ArrayList<>();
        for (int i = 0; i < devices; i++) visits.add(receivedVisit());

        ExecutorService pool = Executors.newFixedThreadPool(devices);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PreliminaryAnalysisResponse>> results = new ArrayList<>();
            for (int i = 0; i < devices; i++) {
                Visit v = visits.get(i);
                int n = 10 + i;
                AnalysisType task = i % 2 == 0 ? AnalysisType.FACIAL : AnalysisType.SKIN_LESION;
                results.add(pool.submit(() -> {
                    start.await();
                    return kioskService.analyze(v.getKioskToken(), photo(n), task);
                }));
            }
            start.countDown();

            for (int i = 0; i < devices; i++) {
                Visit v = visits.get(i);
                int n = 10 + i;
                List<String> classes = i % 2 == 0 ? FACIAL : SKIN;
                String expected = classes.get(n % classes.size());

                PreliminaryAnalysisResponse tablet = results.get(i).get(20, TimeUnit.SECONDS);
                assertThat(tablet.topK().get(0).diseaseCode()).isEqualTo(expected);
                assertThat(tablet.analysisType()).isEqualTo(i % 2 == 0 ? "facial" : "skin_lesion");

                PreliminaryAnalysisResponse doctor = kioskService.findByVisitId(v.getId());
                assertThat(doctor.topK().get(0).diseaseCode()).isEqualTo(expected);
                assertThat(doctor.analysisType()).isEqualTo(tablet.analysisType());

                mockMvc.perform(get("/api/kiosk/session/{token}/heatmap", v.getKioskToken()))
                        .andExpect(status().isOk())
                        .andExpect(content().bytes(heatmapOf("photo-" + n)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 참고 소견은 결과를 그린 뒤 따로 만들어 저장되고, 의사 화면에도 같은 소견이 보인다. 두 번 불러도 다시 만들지 않는다. */
    @Test
    void commentIsMadeAfterTheResultAndKept() throws Exception {
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();
        kioskService.analyze(token, photo(7), AnalysisType.FACIAL);
        assertThat(preliminaryAnalysisRepository.findByVisitId(visit.getId()).orElseThrow().getAiComment()).isNull();

        String comment = JSON.readTree(mockMvc.perform(post("/api/kiosk/session/{token}/comment", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("aiComment").asText();
        assertThat(comment).isNotBlank();
        assertThat(kioskService.findByVisitId(visit.getId()).aiComment()).isEqualTo(comment);

        // 이미 있으면 그대로 준다.
        mockMvc.perform(post("/api/kiosk/session/{token}/comment", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiComment").value(comment));

        // 재촬영하면 옛 소견은 지워진다 — 새 사진 옆에 옛 사진의 소견이 남으면 안 된다.
        kioskService.analyze(token, photo(8), AnalysisType.FACIAL);
        assertThat(kioskService.findByVisitId(visit.getId()).aiComment()).isNull();
    }

    /** 소견을 만드는 사이 재촬영이 끝났다면, 늦게 온 소견(이전 사진의 것)은 새 분석에 붙지 않는다. */
    @Test
    void lateCommentForAnOlderPhotoIsNotSaved() {
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();
        kioskService.analyze(token, photo(1), AnalysisType.FACIAL);
        String olderKey = preliminaryAnalysisRepository.findByVisitId(visit.getId()).orElseThrow().getGradcamUrl();
        kioskService.analyze(token, photo(2), AnalysisType.FACIAL);

        assertThat(kioskTransactionService.saveAiComment(visit.getId(), olderKey, "이전 사진의 소견")).isFalse();
        assertThat(kioskService.findByVisitId(visit.getId()).aiComment()).isNull();
    }

    @Test
    void commentBeforeAnyAnalysisIsNotFound() throws Exception {
        mockMvc.perform(post("/api/kiosk/session/{token}/comment", receivedVisit().getKioskToken()))
                .andExpect(status().isNotFound());
    }

    private static byte[] heatmapOf(String photo) {
        return ("heat-" + photo).getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static MockMultipartFile photo(int n) {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", ("photo-" + n).getBytes(StandardCharsets.UTF_8));
    }

    private Visit receivedVisit() {
        Patient patient = patientRepository.save(Patient.builder()
                .name("부스방문자").birthDate(LocalDate.of(2001, 3, 3)).gender(Gender.F).build());
        // CHAR(12) — 실제 발급 토큰처럼 12자로 맞춘다.
        String token = "KIOSKBOOTH%02d".formatted(TOKEN_SEQ.incrementAndGet());
        return visitRepository.save(Visit.builder()
                .patientId(patient.getId())
                .visitDate(LocalDateTime.now())
                .status(VisitStatus.RECEIVED)
                .kioskToken(token)
                .build());
    }

    private String tokenFor(MemberRole role) {
        return jwtUtil.generate(1L, "kiosk-booth-" + role.name().toLowerCase(), role.name());
    }
}
