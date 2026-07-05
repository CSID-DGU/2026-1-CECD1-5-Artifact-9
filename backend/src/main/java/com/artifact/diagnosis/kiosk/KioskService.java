package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.InvalidAnalysisImageException;
import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.image.ImageStorageService;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.prescription.GeminiService;
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

    @Value("${fastapi.url:http://localhost:8000}")
    private String fastapiUrl;

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

    /** 접수 목록에서 가장 최근에 RECEIVED 상태이면서 예비분석이 아직 없는 Visit 1건. 데모는 동시 1명 기준. */
    @Transactional(readOnly = true)
    public KioskPendingResponse findPending() {
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

        Patient patient = patientRepository.findById(pendingVisit.getPatientId())
                .orElseThrow(() -> new NoSuchElementException("환자를 찾을 수 없습니다. id=" + pendingVisit.getPatientId()));

        return new KioskPendingResponse(
                pendingVisit.getId(),
                patient.getName(),
                "V" + String.format("%05d", pendingVisit.getId())
        );
    }

    /**
     * 태블릿에서 선택한 이미지로 예비분석을 수행한다.
     * Top-K + GradCAM은 FastAPI에서, 참고 소견은 Gemini에서 받아 preliminary_analysis에 저장(upsert)한다.
     * Visit 상태는 변경하지 않는다(RECEIVED 유지) — 정식 진료 흐름과 완전히 분리된 채널이다.
     */
    @Transactional
    public PreliminaryAnalysisResponse analyze(Long visitId, MultipartFile file) {
        visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다. id=" + visitId));

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

        return toResponse(entity, prediction);
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
                heatmapApiUrl(entity),
                entity.getAiComment(),
                entity.getAnalyzedAt()
        );
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getHeatmapContent(Long visitId) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .filter(p -> p.getGradcamUrl() != null)
                .orElseThrow(() -> new NoSuchElementException("예비분석 히트맵이 없습니다. visitId=" + visitId));
        byte[] bytes = imageStorageService.download(entity.getGradcamUrl());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=31536000, immutable")
                .body(bytes);
    }

    private PreliminaryAnalysisResponse toResponse(PreliminaryAnalysis entity, FastApiPredictResponse prediction) {
        List<PreliminaryAnalysisResponse.TopKResult> topK = prediction.top5().stream()
                .map(r -> new PreliminaryAnalysisResponse.TopKResult(r.diseaseCode(), r.diseaseNameKo(), r.confidence()))
                .toList();

        return new PreliminaryAnalysisResponse(
                topK,
                heatmapApiUrl(entity),
                entity.getAiComment(),
                entity.getAnalyzedAt()
        );
    }

    private static String heatmapApiUrl(PreliminaryAnalysis entity) {
        if (entity.getGradcamUrl() == null) return null;
        return "/api/kiosk/preliminary/" + entity.getVisitId() + "/heatmap";
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
