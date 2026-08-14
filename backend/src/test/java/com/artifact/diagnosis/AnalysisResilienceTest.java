package com.artifact.diagnosis;

import com.artifact.diagnosis.analysis.AiServiceUnavailableException;
import com.artifact.diagnosis.analysis.AnalysisService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 분석이 외부 장애를 만났을 때의 동작 검증.
 *
 * <p>여기서 재현하는 것은 "FastAPI가 죽은" 상황이 아니라 <b>"살아 있는데 응답하지 않는"</b> 상황이다.
 * 죽으면 연결이 즉시 거절돼 바로 실패하지만, 멈추기만 하면 타임아웃이 없는 한 요청 스레드가 영원히 묶인다.
 * 진료실 입장에서는 후자가 훨씬 흔하고 훨씬 나쁘다 — 서버는 살아 있는데 화면 전체가 먹통이 된다.
 *
 * <p>본 테스트 클래스가 별도 컨텍스트를 쓰는 이유는 {@code fastapi.url} 을 가짜 서버로 바꿔야 하기 때문이다.
 */
@SpringBootTest
class AnalysisResilienceTest {

    /** 요청을 받고도 응답하지 않는 가짜 FastAPI. */
    private static HttpServer hangingServer;

    private static final int TIMEOUT_SECONDS = 2;

    @BeforeAll
    static void startHangingServer() throws IOException {
        hangingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hangingServer.createContext("/", exchange -> {
            try {
                Thread.sleep(60_000);   // 응답하지 않는다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 데몬 스레드로 돌린다 — 위 sleep이 남아 있어도 테스트 JVM 종료를 막지 않는다.
        hangingServer.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hanging-fastapi");
            t.setDaemon(true);
            return t;
        }));
        hangingServer.start();
    }

    @AfterAll
    static void stopHangingServer() {
        if (hangingServer != null) hangingServer.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fastapi.url", () -> "http://127.0.0.1:" + hangingServer.getAddress().getPort());
        // 실제 운영값은 30초다. 테스트에서까지 30초를 기다릴 이유는 없으므로 짧게 줄여 같은 경로를 탄다.
        registry.add("fastapi.timeout-seconds", () -> TIMEOUT_SECONDS);

        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:artifact_resilience;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("cloud.aws.credentials.access-key", () -> "test-access-key");
        registry.add("cloud.aws.credentials.secret-key", () -> "test-secret-key");
        registry.add("cloud.aws.s3.bucket", () -> "test-bucket");
        registry.add("image.storage.type", () -> "local");
        registry.add("jwt.secret", () -> "test-only-dummy-signing-key-not-used-anywhere-else-0123456789");
        registry.add("fastapi.internal-secret", () -> "test-only-internal-secret");
    }

    @Autowired
    AnalysisService analysisService;

    @Autowired
    PatientRepository patientRepository;

    @Autowired
    VisitRepository visitRepository;

    @Autowired
    VisitImageRepository visitImageRepository;

    @Autowired
    ImageStorageService imageStorageService;

    /**
     * 응답하지 않는 추론 서버를 만나면 제한 시간 안에 503으로 끊고,
     * 접수 상태는 분석 시작 전으로 되돌아가야 한다.
     *
     * <p>상태 복구가 특히 중요하다. ANALYZING 에 갇히면 {@code markAnalyzing()} 이 그 상태를 거부하므로
     * 재시도조차 막히고, 의사는 DB를 직접 고치기 전까지 그 환자를 진행시킬 수 없다.
     */
    @Test
    void analyzeFailsFastWhenInferenceServerHangs() {
        Patient patient = patientRepository.save(Patient.builder()
                .name("최환자")
                .birthDate(LocalDate.of(1995, 5, 5))
                .gender(Gender.F)
                .build());
        Visit visit = visitRepository.save(Visit.builder()
                .patientId(patient.getId())
                .visitDate(LocalDateTime.of(2026, 6, 4, 11, 0))
                .status(VisitStatus.IN_PROGRESS)
                .build());
        VisitImage image = visitImageRepository.save(VisitImage.builder()
                .visitId(visit.getId())
                .imageUrl(storeDummyImage())
                .uploadedAt(LocalDateTime.now())
                .build());

        long startMs = System.currentTimeMillis();
        assertThatThrownBy(() -> analysisService.analyze(visit.getId(), List.of(image.getId())))
                .isInstanceOf(AiServiceUnavailableException.class);
        long elapsedMs = System.currentTimeMillis() - startMs;

        // 타임아웃이 없던 시절에는 여기서 영원히 멈춰 있었다.
        assertThat(elapsedMs).isLessThan(TIMEOUT_SECONDS * 1000L + 5_000L);

        assertThat(visitRepository.findById(visit.getId()).orElseThrow().getStatus())
                .isEqualTo(VisitStatus.IN_PROGRESS);
    }

    /** 컨트롤러의 {@code @NotEmpty} 를 거치지 않는 호출 경로에서도 500이 아니라 400으로 끊어야 한다. */
    @Test
    void analyzeRejectsEmptyImageIds() {
        assertThatThrownBy(() -> analysisService.analyze(1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analysisService.analyze(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 추론 호출 전에 스토리지에서 원본을 읽으므로, 내용은 아무거나여도 파일 자체는 존재해야 한다. */
    private String storeDummyImage() {
        return imageStorageService.uploadBytes(
                "test/analysis-resilience.jpg", new byte[]{1, 2, 3}, "image/jpeg");
    }
}
