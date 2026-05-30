package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.image.ImageStorageService;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitImage;
import com.artifact.diagnosis.visit.VisitImageRepository;
import com.artifact.diagnosis.visit.VisitRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final VisitRepository visitRepository;
    private final VisitImageRepository visitImageRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final DiseaseRepository diseaseRepository;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    @Value("${fastapi.url:http://localhost:8000}")
    private String fastapiUrl;

    private static final String MODEL_VERSION = "efficientnet_b0_v1";

    private static final Map<String, String> DISEASE_NAME_KO = Map.of(
            "akiec", "광선각화증/상피내암",
            "bcc",   "기저세포암",
            "bkl",   "양성 각화증성 병변",
            "df",    "피부섬유종",
            "mel",   "악성 흑색종",
            "nv",    "멜라닌세포모반",
            "vasc",  "혈관성 병변"
    );

    private static final Map<String, String> DISEASE_REASON = Map.of(
            "nv",    "균일한 색소 분포와 규칙적인 경계선이 멜라닌세포모반의 특성과 일치합니다. 대부분 양성이나 크기 변화 시 추적 관찰이 필요합니다.",
            "mel",   "불규칙한 색상 분포와 비대칭적 경계 패턴이 악성 흑색종의 특성과 일치합니다.",
            "bkl",   "각질층의 과증식 및 지루성 표면 패턴이 관찰됩니다. 양성 경과가 많으나 급격한 변화 시 조직 검사를 권장합니다.",
            "bcc",   "진주빛 경계와 확장된 모세혈관 패턴이 기저세포암의 특성과 일치합니다. 조기 외과적 제거가 권장됩니다.",
            "akiec", "표피 세포의 이형성 패턴이 광선각화증/상피내암의 특성과 일치합니다. 전암성 병변으로 조기 치료가 중요합니다.",
            "df",    "피부 내 경계 명확한 섬유성 결절 패턴이 피부섬유종의 특성과 일치합니다. 양성이며 경과 관찰만으로 충분합니다.",
            "vasc",  "혈관 확장 및 혈관 내 이상 패턴이 감지되었습니다. 혈관 레이저 치료를 고려할 수 있습니다."
    );

    @Transactional
    public AnalysisResponse analyze(Long visitId, List<Long> imageIds) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));

        visit.markAnalyzing();

        VisitImage image = visitImageRepository.findById(imageIds.get(0))
                .orElseThrow(() -> new NoSuchElementException("이미지를 찾을 수 없습니다: " + imageIds.get(0)));

        long startMs = System.currentTimeMillis();
        FastApiPredictResponse prediction = callFastApi(image.getImageUrl());
        int inferenceMs = (int) (System.currentTimeMillis() - startMs);

        if (!prediction.isValidOrDefault()) {
            visit.rollbackAnalysis();
            throw new InvalidAnalysisImageException(prediction.messageOrDefault());
        }

        Disease disease = diseaseRepository.findByDiseaseCode(prediction.top1().diseaseCode())
                .orElseThrow(() -> new IllegalStateException("알 수 없는 병명 코드: " + prediction.top1().diseaseCode()));

        List<TopKItem> topK = prediction.top5().stream()
                .map(r -> new TopKItem(r.diseaseCode(), r.confidence()))
                .toList();

        AnalysisResult result = AnalysisResult.builder()
                .visitId(visitId)
                .modelVersion(MODEL_VERSION)
                .predictedDiseaseId(disease.getId())
                .confidence(BigDecimal.valueOf(prediction.top1().confidence()))
                .topKResults(topK)
                .inferenceTimeMs(inferenceMs)
                .build();

        analysisResultRepository.save(result);
        visit.markAnalyzed();

        log.info("분석 완료 visitId={} top1={} confidence={}", visitId,
                prediction.top1().diseaseCode(), prediction.top1().confidence());

        return toResponse(result, prediction);
    }

    @Transactional(readOnly = true)
    public AnalysisResponse getLatest(Long visitId) {
        AnalysisResult result = analysisResultRepository
                .findFirstByVisitIdOrderByAnalyzedAtDesc(visitId)
                .orElseThrow(() -> new NoSuchElementException("분석 결과가 없습니다: " + visitId));

        Disease disease = diseaseRepository.findById(result.getPredictedDiseaseId())
                .orElseThrow();

        List<AnalysisResponse.TopKResult> top5 = result.getTopKResults().stream()
                .map(item -> new AnalysisResponse.TopKResult(
                        result.getTopKResults().indexOf(item) + 1,
                        item.code(),
                        DISEASE_NAME_KO.getOrDefault(item.code(), item.code()),
                        item.confidence(),
                        DISEASE_REASON.getOrDefault(item.code(), "")
                ))
                .toList();

        return new AnalysisResponse(
                result.getId(),
                result.getVisitId(),
                result.getModelVersion(),
                new AnalysisResponse.Top1Result(
                        disease.getDiseaseCode(),
                        DISEASE_NAME_KO.getOrDefault(disease.getDiseaseCode(), disease.getDiseaseCode()),
                        result.getConfidence()
                ),
                top5,
                result.getInferenceTimeMs(),
                result.getAnalyzedAt()
        );
    }

    private FastApiPredictResponse callFastApi(String imageUrl) {
        try {
            byte[] imageBytes = imageStorageService.download(imageUrl);
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
            throw new RuntimeException("FastAPI 분석 요청 실패: " + e.getMessage(), e);
        }
    }

    private AnalysisResponse toResponse(AnalysisResult result, FastApiPredictResponse prediction) {
        List<AnalysisResponse.TopKResult> top5 = prediction.top5().stream()
                .map(r -> new AnalysisResponse.TopKResult(
                        r.rank(), r.diseaseCode(),
                        DISEASE_NAME_KO.getOrDefault(r.diseaseCode(), r.diseaseCode()),
                        r.confidence(),
                        DISEASE_REASON.getOrDefault(r.diseaseCode(), "")
                ))
                .toList();

        return new AnalysisResponse(
                result.getId(),
                result.getVisitId(),
                result.getModelVersion(),
                new AnalysisResponse.Top1Result(
                        prediction.top1().diseaseCode(),
                        DISEASE_NAME_KO.getOrDefault(prediction.top1().diseaseCode(), prediction.top1().diseaseCode()),
                        result.getConfidence()
                ),
                top5,
                result.getInferenceTimeMs(),
                result.getAnalyzedAt()
        );
    }

    // FastAPI 응답 구조 (snake_case → camelCase 매핑)
    private record FastApiPredictResponse(
            @JsonProperty("is_valid") Boolean isValid,
            String message,
            Double threshold,
            FastApiTop1 top1,
            List<FastApiTop5Item> top5
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
