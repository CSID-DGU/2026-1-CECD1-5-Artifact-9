package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.disease.Severity;
import com.artifact.diagnosis.image.ImageStorageService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확대 히트맵(crops)과 확대 재판정 기록이 FastAPI 응답 → 스토리지·DB → 분석 응답·조회 API 까지 이어지는지.
 *
 * 가짜 FastAPI 가 정해 둔 응답을 돌려준다. 확대 보기는 실패해도 분석이 나가도록 짜여 있어서
 * (AnalysisService.uploadHeatmaps) 고장 나면 조용히 사라진다 — 화면이 아니라 여기서 잡는다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class AnalysisHeatmapCropTest {

    private static HttpServer fakeFastApi;
    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();

    private static final byte[] FULL_HEATMAP = "full-heatmap".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CROP_1 = "crop-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CROP_3 = "crop-3".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void startFakeFastApi() throws IOException {
        fakeFastApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeFastApi.createContext("/predict-base64", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
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
                () -> "jdbc:h2:mem:artifact_heatmap_crops;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
    }

    @Autowired AnalysisService analysisService;
    @Autowired AnalysisResultRepository analysisResultRepository;
    @Autowired DiseaseRepository diseaseRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired VisitImageRepository visitImageRepository;
    @Autowired ImageStorageService imageStorageService;

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

    /**
     * 원본만으로는 nv 38% 라 확대 영역을 섞어 mel 52% 로 다시 판정한 응답.
     * 2번 확대 영역은 base64 가 깨져 있다 — 그 하나만 빠지고 분석은 나가야 한다.
     */
    @Test
    void cropsAndFullImageTop1FlowThroughStorageAndBack() {
        RESPONSE.set("""
                {"confidence_level":"low","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"mel","disease_name_ko":"악성 흑색종","confidence":0.52},
                 "top5":[{"rank":1,"disease_code":"mel","disease_name_ko":"악성 흑색종","confidence":0.52},
                         {"rank":2,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.31}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion",
                 "heatmap_style":"layercam-flip-jet-v2",
                 "crops":[{"rank":1,"box_norm":[0.1,0.2,0.5,0.6],"peak":1.0,"heatmap_base64":"%s"},
                          {"rank":2,"box_norm":[0.6,0.1,0.9,0.4],"peak":0.8,"heatmap_base64":"@@ not base64 @@"},
                          {"rank":3,"box_norm":[0.4,0.5,0.7,0.8],"peak":0.74,"heatmap_base64":"%s"}],
                 "full_image_top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.38}}
                """.formatted(b64(FULL_HEATMAP), b64(CROP_1), b64(CROP_3)));
        Visit visit = visitWithImage();

        AnalysisResponse analyzed = analysisService.analyze(visit.getId(), List.of(imageOf(visit)));

        String cropBase = "/api/v1/visits/" + visit.getId() + "/analysis/heatmap/crops/";
        assertThat(analyzed.heatmapCrops())
                .extracting(AnalysisResponse.HeatmapCropResult::rank, AnalysisResponse.HeatmapCropResult::imageUrl)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, cropBase + 1),
                        org.assertj.core.groups.Tuple.tuple(3, cropBase + 3));
        assertThat(analyzed.heatmapCrops().get(1).boxNorm()).containsExactly(0.4, 0.5, 0.7, 0.8);
        assertThat(analyzed.heatmapStyle()).isEqualTo("layercam-flip-jet-v2");
        assertThat(analyzed.top1().diseaseCode()).isEqualTo("mel");
        assertThat(analyzed.confidenceLevel()).isEqualTo("low");
        assertThat(analyzed.fullImageTop1()).isEqualTo(new AnalysisResponse.FullImageTop1("nv", "멜라닌세포모반", 0.38));

        // 다시 조회해도(새로고침·다른 화면) 같은 것이 나온다 — DB 에 남았다는 뜻이다.
        AnalysisResponse latest = analysisService.getLatest(visit.getId());
        assertThat(latest.heatmapCrops()).isEqualTo(analyzed.heatmapCrops());
        assertThat(latest.fullImageTop1()).isEqualTo(analyzed.fullImageTop1());
        assertThat(latest.heatmapStyle()).isEqualTo("layercam-flip-jet-v2");

        assertThat(analysisService.getHeatmapCropContent(visit.getId(), 3).getBody()).isEqualTo(CROP_3);
        assertThat(analysisService.getHeatmapCropContent(visit.getId(), 1).getBody()).isEqualTo(CROP_1);
        assertThat(analysisService.getHeatmapContent(visit.getId()).getBody()).isEqualTo(FULL_HEATMAP);
        assertThatThrownBy(() -> analysisService.getHeatmapCropContent(visit.getId(), 2))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(visitRepository.findById(visit.getId()).orElseThrow().getStatus()).isEqualTo(VisitStatus.ANALYZED);
    }

    /** 확대 보기가 생기기 전의 FastAPI 응답 — 필드가 아예 없다. 분석은 전과 같고 확대 보기만 비어 있다. */
    @Test
    void olderFastApiWithoutCropsStillAnalyzes() {
        RESPONSE.set("""
                {"confidence_level":"normal","low_confidence_threshold":0.45,
                 "top1":{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.91},
                 "top5":[{"rank":1,"disease_code":"nv","disease_name_ko":"멜라닌세포모반","confidence":0.91}],
                 "heatmap_base64":"%s","model_version":"efficientnet_b0-test","task":"skin_lesion"}
                """.formatted(b64(FULL_HEATMAP)));
        Visit visit = visitWithImage();

        AnalysisResponse analyzed = analysisService.analyze(visit.getId(), List.of(imageOf(visit)));

        assertThat(analyzed.heatmapCrops()).isEmpty();
        assertThat(analyzed.fullImageTop1()).isNull();
        assertThat(analyzed.heatmapStyle()).isNull();
        assertThat(analyzed.heatmapImageUrl()).isNotNull();
        AnalysisResult saved = analysisResultRepository.findFirstByVisitIdOrderByAnalyzedAtDesc(visit.getId()).orElseThrow();
        assertThat(saved.getHeatmapCrops()).isNull();
        assertThat(saved.getFullImageTop1()).isNull();
        assertThatThrownBy(() -> analysisService.getHeatmapCropContent(visit.getId(), 1))
                .isInstanceOf(NoSuchElementException.class);
    }

    private Visit visitWithImage() {
        Patient patient = patientRepository.save(Patient.builder()
                .name("확대환자").birthDate(LocalDate.of(1990, 1, 1)).gender(Gender.M).build());
        Visit visit = visitRepository.save(Visit.builder()
                .patientId(patient.getId())
                .visitDate(LocalDateTime.of(2026, 10, 28, 10, 0))
                .status(VisitStatus.IN_PROGRESS)
                .build());
        visitImageRepository.save(VisitImage.builder()
                .visitId(visit.getId())
                .imageUrl(imageStorageService.uploadBytes("test/heatmap-crop-" + visit.getId() + ".jpg",
                        new byte[]{1, 2, 3}, "image/jpeg"))
                .uploadedAt(LocalDateTime.now())
                .build());
        return visit;
    }

    private Long imageOf(Visit visit) {
        return visitImageRepository.findAll().stream()
                .filter(i -> i.getVisitId().equals(visit.getId()))
                .findFirst().orElseThrow().getId();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
