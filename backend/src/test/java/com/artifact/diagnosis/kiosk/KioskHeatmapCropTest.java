package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.AnalysisResponse;
import com.artifact.diagnosis.common.jwt.JwtUtil;
import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.disease.Severity;
import com.artifact.diagnosis.member.MemberRole;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.groups.Tuple;
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
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 키오스크 예비분석의 확대 히트맵과 확대 재판정 기록이 태블릿(토큰 경로)과 의사 화면(접수 경로)
 * 양쪽에 같은 내용으로 이어지는지. 가짜 FastAPI 가 정해 둔 응답을 돌려준다.
 *
 * 태블릿에는 JWT 가 없어서 확대 히트맵 경로까지 토큰으로 내려가야 하고, 의사 화면은 반대로 토큰을 모른다.
 * 한쪽 경로만 맞으면 화면 하나는 회색 칸만 보게 된다 — 두 경로를 함께 본다.
 *
 * 가짜 FastAPI 는 분석마다 다른 그림 바이트를 낸다(n 번째 요청이면 "crop-1-n"). 모든 분석이 같은 바이트면
 * 다른 접수의 그림이나 재촬영 전 옛 그림이 나가도 내용으로는 가려지지 않는다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest
class KioskHeatmapCropTest {

    private static HttpServer fakeFastApi;
    /** n 번째 요청에 돌려줄 응답. */
    private static final AtomicReference<IntFunction<String>> RESPONSE = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicInteger TOKEN_SEQ = new AtomicInteger();

    /** 원본 nv 38% 라 확대 영역을 섞어 mel 52% 로 다시 판정한 응답. 2번 확대 영역은 base64 가 깨져 있다. */
    private static String rejudged(int n) {
        return """
                {"confidence_level":"low","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"mel","disease_name_ko":"악성 흑색종","confidence":0.52},
                 "top5":[{"rank":1,"disease_code":"mel","disease_name_ko":"악성 흑색종","confidence":0.52},
                         {"rank":2,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.31}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion",
                 "heatmap_style":"layercam-flip-jet-v2",
                 "crops":[{"rank":1,"box_norm":[0.1,0.2,0.5,0.6],"peak":1.0,"heatmap_base64":"%s","source":"reinfer"},
                          {"rank":2,"box_norm":[0.6,0.1,0.9,0.4],"peak":0.8,"heatmap_base64":"@@ not base64 @@","source":"reinfer"},
                          {"rank":3,"box_norm":[0.4,0.5,0.7,0.8],"peak":0.74,"heatmap_base64":"%s","source":"reinfer"}],
                 "full_image_top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.38}}
                """.formatted(b64(fullHeatmap(n)), b64(crop(1, n)), b64(crop(3, n)));
    }

    /** 확대 영역이 한 곳뿐인 재판정 응답. 상자 자리도 위와 다르다. */
    private static String singleCrop(int n) {
        return """
                {"confidence_level":"low","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.44},
                 "top5":[{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.44}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion",
                 "heatmap_style":"layercam-flip-jet-v2",
                 "crops":[{"rank":1,"box_norm":[0.3,0.3,0.8,0.8],"peak":1.0,"heatmap_base64":"%s","source":"reinfer"}],
                 "full_image_top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.41}}
                """.formatted(b64(fullHeatmap(n)), b64(crop(1, n)));
    }

    /** 확대 보기가 생기기 전의 FastAPI 응답 — 필드가 아예 없다. */
    private static String withoutCrops(int n) {
        return """
                {"confidence_level":"normal","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.91},
                 "top5":[{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.91}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion"}
                """.formatted(b64(fullHeatmap(n)));
    }

    @BeforeAll
    static void startFakeFastApi() throws IOException {
        fakeFastApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFastApi.createContext("/predict-base64", exchange -> {
            LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = RESPONSE.get().apply(REQUESTS.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fastapi.url", () -> "http://127.0.0.1:" + fakeFastApi.getAddress().getPort());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:artifact_kiosk_crops;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
        // 이 클래스는 분석마다 참고 소견(LLM)까지 부르므로, 비우지 않으면 한 번 돌 때마다 유료 호출이 여러 번 나간다.
        registry.add("gemini.api.key", () -> "");
        registry.add("anthropic.api.key", () -> "");
    }

    @Autowired KioskService kioskService;
    @Autowired DiseaseRepository diseaseRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;

    /** 경고 문구의 강도가 심각도에 달려 있어서 심어 둔다 — 없으면 "모르면 위험"으로 늘 위험 문구가 나온다. */
    @BeforeEach
    void seedDiseases() {
        for (String code : List.of("mel", "nv")) {
            if (diseaseRepository.findByDiseaseCode(code).isEmpty()) {
                diseaseRepository.save(Disease.builder()
                        .diseaseCode(code).nameKo(code).severity("mel".equals(code) ? Severity.HIGH : Severity.LOW)
                        .build());
            }
        }
    }

    @Test
    void cropsReachTheTabletByTokenAndTheDoctorByVisit() throws Exception {
        RESPONSE.set(KioskHeatmapCropTest::rejudged);
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();

        PreliminaryAnalysisResponse tablet = kioskService.analyze(token, photo());
        int n = REQUESTS.get();

        String tabletBase = "/api/kiosk/session/" + token + "/heatmap/crops/";
        assertThat(tablet.heatmapCrops())
                .extracting(AnalysisResponse.HeatmapCropResult::rank, AnalysisResponse.HeatmapCropResult::imageUrl)
                .containsExactly(Tuple.tuple(1, tabletBase + 1), Tuple.tuple(3, tabletBase + 3));
        assertThat(tablet.heatmapCrops().get(1).boxNorm()).containsExactly(0.4, 0.5, 0.7, 0.8);
        assertThat(tablet.heatmapStyle()).isEqualTo("layercam-flip-jet-v2");
        assertThat(tablet.topK().get(0).diseaseCode()).isEqualTo("mel");
        assertThat(tablet.confidenceLevel()).isEqualTo("low");
        assertThat(tablet.fullImageTop1()).isEqualTo(new AnalysisResponse.FullImageTop1("nv", "멜라닌세포모반", 0.38));
        assertThat(tablet.heatmapCrops()).extracting(AnalysisResponse.HeatmapCropResult::source).containsOnly("reinfer");
        // 이제 키오스크도 확대 보기·재판정을 켜서 부른다(그 결과를 담을 칸이 생겼다). 끈 채로 남으면 위 칸이 늘 비어 있다.
        assertThat(LAST_REQUEST.get()).doesNotContain("\"crops\"");

        // 의사 화면은 같은 내용을 접수 경로로 받는다.
        PreliminaryAnalysisResponse doctor = kioskService.findByVisitId(visit.getId());
        String doctorBase = "/api/v1/visits/" + visit.getId() + "/preliminary/heatmap/crops/";
        assertThat(doctor.heatmapCrops())
                .extracting(AnalysisResponse.HeatmapCropResult::rank, AnalysisResponse.HeatmapCropResult::imageUrl)
                .containsExactly(Tuple.tuple(1, doctorBase + 1), Tuple.tuple(3, doctorBase + 3));
        assertThat(doctor.fullImageTop1()).isEqualTo(tablet.fullImageTop1());
        assertThat(doctor.heatmapStyle()).isEqualTo("layercam-flip-jet-v2");

        // 응답이 알려 준 경로를 실제로 부른다 — 컨트롤러가 번호를 그대로 넘기는지, 전체 히트맵을 내주지 않는지.
        // 태블릿 경로는 로그인 없이 열린다.
        mockMvc.perform(get(tabletBase + 3))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", startsWith("private")))
                .andExpect(content().bytes(crop(3, n)));
        mockMvc.perform(get(tabletBase + 1)).andExpect(content().bytes(crop(1, n)));
        mockMvc.perform(get("/api/kiosk/session/" + token + "/heatmap")).andExpect(content().bytes(fullHeatmap(n)));
        // 깨져서 건너뛴 2번은 없다.
        mockMvc.perform(get(tabletBase + 2)).andExpect(status().isNotFound());
        // 토큰을 모르면 어떤 확대 히트맵에도 닿지 못한다.
        mockMvc.perform(get("/api/kiosk/session/ZZZZZZZZZZZZ/heatmap/crops/1")).andExpect(status().isNotFound());

        // 의사 화면 경로는 진료 직군이면 열린다 — 간호사도 진료실 화면을 쓴다.
        for (MemberRole role : List.of(MemberRole.NURSE, MemberRole.DOCTOR)) {
            mockMvc.perform(get(doctorBase + 1).header("Authorization", "Bearer " + tokenFor(role)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/jpeg"))
                    .andExpect(content().bytes(crop(1, n)));
        }
        mockMvc.perform(get(doctorBase + 3).header("Authorization", "Bearer " + tokenFor(MemberRole.DOCTOR)))
                .andExpect(content().bytes(crop(3, n)));
        mockMvc.perform(get(doctorBase + 2).header("Authorization", "Bearer " + tokenFor(MemberRole.DOCTOR)))
                .andExpect(status().isNotFound());
    }

    /** 재촬영하면 새 분석이 덮어쓴다. 새 분석에 확대 보기가 없으면 옛 확대 히트맵도 지워져야 한다. */
    @Test
    void retakeWithoutCropsClearsTheOldCrops() {
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();
        RESPONSE.set(KioskHeatmapCropTest::rejudged);
        kioskService.analyze(token, photo());

        RESPONSE.set(KioskHeatmapCropTest::withoutCrops);
        PreliminaryAnalysisResponse retaken = kioskService.analyze(token, photo());
        int retake = REQUESTS.get();

        assertThat(retaken.heatmapCrops()).isEmpty();
        assertThat(retaken.fullImageTop1()).isNull();
        assertThat(retaken.heatmapStyle()).isNull();
        PreliminaryAnalysisResponse doctor = kioskService.findByVisitId(visit.getId());
        assertThat(doctor.heatmapCrops()).isEmpty();
        assertThat(doctor.fullImageTop1()).isNull();
        assertThatThrownBy(() -> kioskService.getHeatmapCropContentByVisitId(visit.getId(), 1))
                .isInstanceOf(NoSuchElementException.class);
        // 전체 히트맵도 새 사진의 것이다.
        assertThat(kioskService.getHeatmapContentByVisitId(visit.getId()).getBody()).isEqualTo(fullHeatmap(retake));
    }

    /** 확대 보기가 있는 분석끼리 바뀌어도 새 상자·새 그림만 나가고, 옛 분석에만 있던 번호는 사라진다. */
    @Test
    void retakeWithOtherCropsServesOnlyTheNewImages() throws Exception {
        Visit visit = receivedVisit();
        String token = visit.getKioskToken();
        RESPONSE.set(KioskHeatmapCropTest::rejudged);
        kioskService.analyze(token, photo());

        RESPONSE.set(KioskHeatmapCropTest::singleCrop);
        PreliminaryAnalysisResponse retaken = kioskService.analyze(token, photo());
        int retake = REQUESTS.get();

        assertThat(retaken.heatmapCrops()).extracting(AnalysisResponse.HeatmapCropResult::rank).containsExactly(1);
        assertThat(retaken.heatmapCrops().get(0).boxNorm()).containsExactly(0.3, 0.3, 0.8, 0.8);
        assertThat(kioskService.findByVisitId(visit.getId()).heatmapCrops().get(0).boxNorm())
                .containsExactly(0.3, 0.3, 0.8, 0.8);
        String base = "/api/kiosk/session/" + token + "/heatmap";
        mockMvc.perform(get(base)).andExpect(content().bytes(fullHeatmap(retake)));
        mockMvc.perform(get(base + "/crops/1")).andExpect(content().bytes(crop(1, retake)));
        mockMvc.perform(get(base + "/crops/3")).andExpect(status().isNotFound());
    }

    /**
     * 태블릿 경로는 로그인 없이 토큰 하나로 병변 사진을 내준다 — 그 토큰의 접수 것만 나가야 한다.
     *
     * 같은 환자가 오늘 두 번 접수한 경우로 만든다. 두 접수의 환자 id 가 같으니 적어도 한쪽은 접수 id 와
     * 환자 id 가 달라서, 접수 id 자리에 환자 id 를 넘기는 실수도 드러난다. 접수마다 환자를 새로 만들면
     * 두 id 가 나란히 늘어 우연히 같아지고, 그 실수가 그대로 통과한다.
     *
     * 같은 이유로 나중 접수를 먼저 분석한다. 접수 순서대로 분석하면 예비분석 행 id 가 접수 id 와 같아져서,
     * 접수 id 로 행 id 를 찾는 실수(findById)나 의사 화면 경로에 행 id 를 싣는 실수가 그대로 통과한다.
     */
    @Test
    void eachTokenReachesOnlyItsOwnVisitsImages() throws Exception {
        Patient patient = savePatient();
        Visit first = receivedVisit(patient);
        Visit second = receivedVisit(patient);

        // 나중 접수를 먼저 분석한다 — 예비분석 행 id 가 접수 id 와 엇갈려야 접수 id 대신 행 id 로 찾는 실수가 드러난다.
        RESPONSE.set(KioskHeatmapCropTest::rejudged);
        kioskService.analyze(second.getKioskToken(), photo());
        int secondN = REQUESTS.get();
        RESPONSE.set(KioskHeatmapCropTest::singleCrop);
        kioskService.analyze(first.getKioskToken(), photo());
        int firstN = REQUESTS.get();

        String firstBase = "/api/kiosk/session/" + first.getKioskToken() + "/heatmap";
        String secondBase = "/api/kiosk/session/" + second.getKioskToken() + "/heatmap";
        mockMvc.perform(get(firstBase)).andExpect(content().bytes(fullHeatmap(firstN)));
        mockMvc.perform(get(secondBase)).andExpect(content().bytes(fullHeatmap(secondN)));
        mockMvc.perform(get(firstBase + "/crops/1")).andExpect(content().bytes(crop(1, firstN)));
        mockMvc.perform(get(secondBase + "/crops/1")).andExpect(content().bytes(crop(1, secondN)));
        mockMvc.perform(get(secondBase + "/crops/3")).andExpect(content().bytes(crop(3, secondN)));
        // 다른 접수에만 있는 번호는 받지 못한다.
        mockMvc.perform(get(firstBase + "/crops/3")).andExpect(status().isNotFound());
        // 의사 화면도 접수별로 갈린다 — 응답이 알려 준 경로를 그대로 부른다.
        PreliminaryAnalysisResponse doctorFirst = kioskService.findByVisitId(first.getId());
        mockMvc.perform(get(doctorFirst.heatmapCrops().get(0).imageUrl())
                        .header("Authorization", "Bearer " + tokenFor(MemberRole.DOCTOR)))
                .andExpect(content().bytes(crop(1, firstN)));
        mockMvc.perform(get(doctorFirst.gradcamUrl())
                        .header("Authorization", "Bearer " + tokenFor(MemberRole.DOCTOR)))
                .andExpect(content().bytes(fullHeatmap(firstN)));
    }

    /**
     * 원본은 mel 40% 였는데 확대 영역을 섞자 nv 47% 가 1순위가 된 경우. 재판정은 통계적으로 원본과 구별되지 않아서
     * 원본이 짚은 악성 가능성을 지우면 안 된다 — 태블릿과 의사 화면 모두 위험 문구여야 한다.
     * 같은 nv 라도 재판정이 아니었다면 일반 문구다(아래 대조).
     */
    @Test
    void rejudgedAwayFromMalignantKeepsTheDangerCautionOnBothScreens() {
        RESPONSE.set(n -> """
                {"confidence_level":"low","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.47},
                 "top5":[{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.47},
                         {"rank":2,"disease_code":"mel","disease_name_ko":"악성 흑색종","confidence":0.31}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion",
                 "heatmap_style":"layercam-flip-jet-v2","crops":[],
                 "full_image_top1":{"rank":1,"disease_code":"mel","disease_name_ko":"악성 흑색종","confidence":0.40}}
                """.formatted(b64(fullHeatmap(n))));
        Visit rejudged = receivedVisit();

        PreliminaryAnalysisResponse tablet = kioskService.analyze(rejudged.getKioskToken(), photo());

        assertThat(tablet.topK().get(0).diseaseCode()).isEqualTo("nv");
        assertThat(tablet.caution()).contains("악성·전암 가능성");
        assertThat(kioskService.findByVisitId(rejudged.getId()).caution()).isEqualTo(tablet.caution());

        RESPONSE.set(n -> """
                {"confidence_level":"low","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.42},
                 "top5":[{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.42}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion"}
                """.formatted(b64(fullHeatmap(n))));
        Visit plain = receivedVisit();
        assertThat(kioskService.analyze(plain.getKioskToken(), photo()).caution())
                .isNotNull().doesNotContain("악성·전암 가능성");
    }

    private Patient savePatient() {
        return patientRepository.save(Patient.builder()
                .name("대기환자").birthDate(LocalDate.of(1992, 2, 2)).gender(Gender.F).build());
    }

    private Visit receivedVisit() {
        return receivedVisit(savePatient());
    }

    private Visit receivedVisit(Patient patient) {
        // CHAR(12) — 실제 발급 토큰처럼 12자로 맞춘다.
        String token = "KIOSKCROP%03d".formatted(TOKEN_SEQ.incrementAndGet());
        return visitRepository.save(Visit.builder()
                .patientId(patient.getId())
                .visitDate(LocalDateTime.now())
                .status(VisitStatus.RECEIVED)
                .kioskToken(token)
                .build());
    }

    private String tokenFor(MemberRole role) {
        return jwtUtil.generate(1L, "kiosk-crop-" + role.name().toLowerCase(), role.name());
    }

    /** n 번째 분석의 그림 바이트. */
    private static byte[] fullHeatmap(int n) {
        return ("full-heatmap-" + n).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] crop(int rank, int n) {
        return ("crop-" + rank + "-" + n).getBytes(StandardCharsets.UTF_8);
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("file", "lesion.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
