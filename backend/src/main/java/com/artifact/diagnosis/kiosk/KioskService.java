package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.InvalidAnalysisImageException;
import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.image.ImageStorageService;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.prescription.GeminiService;
import com.artifact.diagnosis.visit.KioskTokenGenerator;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 대기실 키오스크 예비분석 서비스.
 * Visit의 정식 진료 FSM과는 분리된 사이드 채널 — 여기서 만든 결과는 visit.status에 영향을 주지 않는다.
 * 현재는 데모 단순성을 위해 동기 처리한다(태블릿이 결과를 기다리는 화면이라 @Async로 분리할 이점이 적음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KioskService {

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final PreliminaryAnalysisRepository preliminaryAnalysisRepository;
    private final ImageStorageService imageStorageService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;
    private final KioskTokenGenerator kioskTokenGenerator;

    @Value("${fastapi.url:http://localhost:8000}")
    private String fastapiUrl;

    /**
     * QR 없이 자동 진입하는 폴백(/api/kiosk/pending)의 활성화 여부. 기본은 꺼짐.
     * 이 엔드포인트만 유일하게 토큰 없이 접근 대상을 알려주므로 — 즉 아무나 호출해 다음 대기 환자의
     * 토큰을 받아갈 수 있으므로 — 시연에서 QR을 못 쓸 때만 KIOSK_AUTO_PENDING=true 로 연다.
     */
    @Value("${kiosk.auto-pending.enabled:false}")
    private boolean autoPendingEnabled;

    /** 예비분석 모델 소스 — 현재는 임상 사진용 단일 모델만 존재. FastAPI에 라우터가 생기면 여기서 전달한다. */
    private static final String SOURCE = "clinic";

    /**
     * disease.name_ko 컬럼은 과거 초기 적재 시 인코딩이 깨져 있어(mojibake) 재조회 시 사용하지 않는다.
     * AnalysisService.DISEASE_NAME_KO 와 동일한 값 — HAM10000 코드 → 한글 병명 하드코딩 매핑.
     */
    private static final Map<String, String> DISEASE_NAME_KO = Map.of(
            "akiec", "광선각화증/상피내암",
            "bcc",   "기저세포암",
            "bkl",   "양성 각화증성 병변",
            "df",    "피부섬유종",
            "mel",   "악성 흑색종",
            "nv",    "멜라닌세포모반",
            "vasc",  "혈관성 병변"
    );

    /**
     * QR 없이 자동 진입하는 폴백(/kiosk?auto=1)용 — 가장 최근 RECEIVED 상태이면서 예비분석이 없는 Visit 1건.
     * 전역으로 "다음 환자 1명"을 정하는 방식이라 동시 1명만 가능하다. 여러 환자를 동시에 진행하려면
     * 접수 화면의 QR(=토큰별 경로)을 써야 한다.
     *
     * <p>기본적으로 꺼져 있다. 인증도 토큰도 없이 호출되는 유일한 경로라, 켜두면 누구나
     * 다음 대기 환자의 키오스크 토큰을 받아 그 환자의 세션·히트맵에 접근할 수 있다.
     * 환자 실명은 응답에서 제외한다 — 태블릿은 토큰으로 이동한 뒤 세션 조회에서 이름을 받는다.
     */
    @Transactional
    public KioskPendingResponse findPending() {
        if (!autoPendingEnabled) {
            throw new NoSuchElementException("자동 진입이 비활성화되어 있습니다. QR 코드를 사용하세요.");
        }

        List<Visit> received = visitRepository.findByStatusOrderByVisitDateAsc(VisitStatus.RECEIVED);

        Visit target = null;
        for (Visit visit : received) {
            if (!preliminaryAnalysisRepository.existsByVisitId(visit.getId())) {
                target = visit;
            }
        }

        if (target == null) {
            throw new NoSuchElementException("예비분석이 필요한 대기 환자가 없습니다.");
        }
        final Visit pendingVisit = target;

        // 컬럼 추가 이전에 만들어진 접수 행은 토큰이 없다 — 폴백도 토큰 경로로 이동하므로 여기서 지연 발급한다.
        if (pendingVisit.getKioskToken() == null) {
            pendingVisit.setKioskToken(kioskTokenGenerator.generate());
        }

        return new KioskPendingResponse(pendingVisit.getKioskToken());
    }

    /** QR 토큰으로 접수 1건을 조회한다. 태블릿의 본인 확인 화면에서 사용. */
    @Transactional(readOnly = true)
    public KioskSessionResponse findSession(String token) {
        Visit visit = resolveToken(token);
        Patient patient = patientRepository.findById(visit.getPatientId())
                .orElseThrow(() -> new NoSuchElementException("환자를 찾을 수 없습니다. id=" + visit.getPatientId()));

        return new KioskSessionResponse(
                visit.getId(),
                patient.getName(),
                "V" + String.format("%05d", visit.getId()),
                preliminaryAnalysisRepository.existsByVisitId(visit.getId())
        );
    }

    /**
     * 태블릿에서 선택한 이미지로 예비분석을 수행한다.
     * Top-K + GradCAM은 FastAPI에서, 참고 소견은 Gemini에서 받아 preliminary_analysis에 저장(upsert)한다.
     * Visit 상태는 변경하지 않는다(RECEIVED 유지) — 정식 진료 흐름과 완전히 분리된 채널이다.
     *
     * visitId가 아니라 토큰을 받는 이유: 이 엔드포인트는 인증이 없어서, visitId(순차 정수)를 그대로
     * 받으면 같은 LAN의 누구나 임의 환자의 예비분석을 덮어쓸 수 있다.
     */
    @Transactional
    public PreliminaryAnalysisResponse analyze(String token, MultipartFile file) {
        Long visitId = resolveToken(token).getId();

        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("이미지 파일을 읽을 수 없습니다.");
        }

        FastApiPredictResponse prediction = callFastApi(imageBytes);
        if (!prediction.isValidOrDefault()) {
            throw new InvalidAnalysisImageException(prediction.messageOrDefault());
        }

        List<TopKItem> topK = prediction.top5().stream()
                .map(r -> new TopKItem(r.diseaseCode(), r.confidence()))
                .toList();

        String gradcamKey = null;
        if (prediction.heatmapBase64() != null) {
            byte[] heatmapBytes = Base64.getDecoder().decode(prediction.heatmapBase64());
            String key = "heatmap/preliminary/" + visitId + "/" + System.currentTimeMillis() + ".jpg";
            gradcamKey = imageStorageService.uploadBytes(key, heatmapBytes, "image/jpeg");
        }

        String aiComment = geminiService.generatePreliminaryComment(topK);

        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .orElseGet(() -> PreliminaryAnalysis.builder().visitId(visitId).source(SOURCE).build());
        entity.setTopKJson(topK);
        entity.setGradcamUrl(gradcamKey);
        entity.setAiComment(aiComment);
        entity.setAnalyzedAt(LocalDateTime.now());
        preliminaryAnalysisRepository.save(entity);

        log.info("예비분석 완료 visitId={} top1={}", visitId, prediction.top1().diseaseCode());

        return toResponse(token, entity, prediction);
    }

    /** 의사 진료 페이지 조회용. 예비분석이 없으면 404(NoSuchElementException). */
    @Transactional(readOnly = true)
    public PreliminaryAnalysisResponse findByVisitId(Long visitId) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .orElseThrow(() -> new NoSuchElementException("예비분석 결과가 없습니다. visitId=" + visitId));

        List<PreliminaryAnalysisResponse.TopKResult> topK = entity.getTopKJson().stream()
                .map(item -> new PreliminaryAnalysisResponse.TopKResult(
                        item.code(),
                        DISEASE_NAME_KO.getOrDefault(item.code(), item.code()),
                        item.confidence()))
                .toList();

        return new PreliminaryAnalysisResponse(
                topK,
                doctorHeatmapApiUrl(entity),
                entity.getAiComment(),
                entity.getAnalyzedAt()
        );
    }

    /** 태블릿용 — 토큰이 가리키는 접수의 히트맵만 반환한다. 인증 없이 열리는 경로다. */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getHeatmapContentByToken(String token) {
        return heatmapResponse(resolveToken(token).getId());
    }

    /** 의사 진료 페이지용 — JWT 인증 경로(/api/v1/visits/{visitId}/preliminary/heatmap)에서 호출한다. */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getHeatmapContentByVisitId(Long visitId) {
        return heatmapResponse(visitId);
    }

    private ResponseEntity<byte[]> heatmapResponse(Long visitId) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .filter(p -> p.getGradcamUrl() != null)
                .orElseThrow(() -> new NoSuchElementException("예비분석 히트맵이 없습니다. visitId=" + visitId));
        byte[] bytes = imageStorageService.download(entity.getGradcamUrl());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                // 환자 병변 이미지다. 공유 캐시(nginx·프록시)에 남지 않도록 private 로 제한한다.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(bytes);
    }

    /** QR 토큰 → Visit. 유효하지 않으면 404 — 태블릿에는 "유효하지 않은 QR"로 표시된다. */
    private Visit resolveToken(String token) {
        if (token == null || token.isBlank()) {
            throw new NoSuchElementException("유효하지 않은 키오스크 토큰입니다.");
        }
        return visitRepository.findByKioskToken(token)
                .orElseThrow(() -> new NoSuchElementException("유효하지 않은 키오스크 토큰입니다."));
    }

    private PreliminaryAnalysisResponse toResponse(String token, PreliminaryAnalysis entity, FastApiPredictResponse prediction) {
        List<PreliminaryAnalysisResponse.TopKResult> topK = prediction.top5().stream()
                .map(r -> new PreliminaryAnalysisResponse.TopKResult(r.diseaseCode(), r.diseaseNameKo(), r.confidence()))
                .toList();

        return new PreliminaryAnalysisResponse(
                topK,
                kioskHeatmapApiUrl(token, entity),
                entity.getAiComment(),
                entity.getAnalyzedAt()
        );
    }

    /**
     * 같은 히트맵을 두 경로로 노출하는 이유: 태블릿은 JWT가 없어 토큰 경로로만 접근할 수 있고,
     * 의사 화면은 반대로 토큰을 모르고 JWT만 갖고 있다. 호출자에 맞는 URL을 응답에 실어준다.
     */
    private static String kioskHeatmapApiUrl(String token, PreliminaryAnalysis entity) {
        if (entity.getGradcamUrl() == null) return null;
        return "/api/kiosk/session/" + token + "/heatmap";
    }

    private static String doctorHeatmapApiUrl(PreliminaryAnalysis entity) {
        if (entity.getGradcamUrl() == null) return null;
        return "/api/v1/visits/" + entity.getVisitId() + "/preliminary/heatmap";
    }

    /** AnalysisService.callFastApi()와 동일한 방식 — 키오스크는 파일에서 바로 바이트를 받으므로 스토리지 다운로드 단계가 없다. */
    private FastApiPredictResponse callFastApi(byte[] imageBytes) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String requestBody = objectMapper.writeValueAsString(Map.of("image_base64", base64Image));

            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fastapiUrl + "/predict-base64"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("FastAPI 오류 " + response.statusCode() + ": " + response.body());
            }

            return objectMapper.readValue(response.body(), FastApiPredictResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("FastAPI 예비분석 요청 실패: " + e.getMessage(), e);
        }
    }

    private record FastApiPredictResponse(
            @JsonProperty("is_valid") Boolean isValid,
            String message,
            Double threshold,
            FastApiTop1 top1,
            List<FastApiTop5Item> top5,
            @JsonProperty("heatmap_base64") String heatmapBase64
    ) {
        private boolean isValidOrDefault() {
            return isValid == null || isValid;
        }

        private String messageOrDefault() {
            return message != null && !message.isBlank()
                    ? message
                    : "AI 분석에 적합하지 않은 이미지입니다.";
        }
    }

    private record FastApiTop1(
            int rank,
            @JsonProperty("disease_code")    String diseaseCode,
            @JsonProperty("disease_name_ko") String diseaseNameKo,
            double confidence
    ) {}

    private record FastApiTop5Item(
            int rank,
            @JsonProperty("disease_code")    String diseaseCode,
            @JsonProperty("disease_name_ko") String diseaseNameKo,
            double confidence
    ) {}
}
