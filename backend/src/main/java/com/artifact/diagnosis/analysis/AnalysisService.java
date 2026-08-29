package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.image.ImageStorageService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisTransactionService analysisTransactionService;
    private final AnalysisResultRepository analysisResultRepository;
    private final DiseaseRepository diseaseRepository;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    /** 요청마다 새로 만들지 않고 공유한다 — {@code HttpClientConfig} 참고. 연결 타임아웃이 걸려 있다. */
    private final HttpClient httpClient;

    @Value("${fastapi.url:http://localhost:8000}")
    private String fastapiUrl;

    /** 추론 응답 대기 한도. GPU 없이 도는 환경도 있어 Gemini(15초)보다 넉넉하게 잡는다. */
    @Value("${fastapi.timeout-seconds:30}")
    private long fastapiTimeoutSeconds;

    /**
     * FastAPI 와 공유하는 내부 호출 시크릿. 기본값을 두지 않는다 — 미설정 시 기동 실패.
     * FastAPI 쪽 {@code INTERNAL_API_SECRET} 과 같은 값이어야 한다.
     */
    @Value("${fastapi.internal-secret}")
    private String fastapiInternalSecret;

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

    /**
     * AI 분석 요청.
     *
     * <p><b>이 메서드에는 의도적으로 {@code @Transactional} 이 없다.</b>
     * FastAPI 추론과 히트맵 업로드는 수 초가 걸리는 외부 호출인데, 트랜잭션 안에서 부르면
     * 그동안 DB 커넥션을 하나씩 붙잡고 있게 된다. 커넥션 풀(기본 10개)이 마르면 분석과 무관한
     * 접수·조회 화면까지 통째로 멈춘다 — 진료실에서는 "서버가 죽었다"로 보인다.
     * DB 작업은 {@link AnalysisTransactionService} 의 짧은 트랜잭션으로 나눠 처리한다.
     *
     * <p>대신 트랜잭션이 실패를 자동으로 되돌려 주지 않으므로, 외부 호출이 실패하면
     * ANALYZING 상태를 직접 복구해야 한다(아래 catch 절).
     */
    public AnalysisResponse analyze(Long visitId, List<Long> imageIds) {
        // 컨트롤러의 @Valid(@NotEmpty)가 HTTP 경로는 이미 막지만, 서비스를 직접 부르는 경로
        // (배치·테스트·향후 내부 호출)에서는 get(0)이 그대로 500으로 터진다.
        if (imageIds == null || imageIds.isEmpty()) {
            throw new IllegalArgumentException("분석할 이미지를 선택해주세요.");
        }

        // (1) 짧은 트랜잭션 — 분석 시작 표시 + 대상 이미지 경로 조회
        AnalysisTransactionService.AnalysisTarget target =
                analysisTransactionService.beginAnalysis(visitId, imageIds.get(0));

        AnalysisResult result;
        FastApiPredictResponse prediction;
        try {
            // (2) 트랜잭션 밖 — 여기서 몇 초가 걸려도 DB 커넥션은 하나도 잡혀 있지 않다
            long startMs = System.currentTimeMillis();
            prediction = callFastApi(target.imageUrl());
            int inferenceMs = (int) (System.currentTimeMillis() - startMs);

            if (!prediction.isValidOrDefault()) {
                throw new InvalidAnalysisImageException(prediction.messageOrDefault());
            }

            List<TopKItem> topK = prediction.top5().stream()
                    .map(r -> new TopKItem(r.diseaseCode(), r.confidence()))
                    .toList();

            String heatmapKey = uploadHeatmap(visitId, prediction);

            // (3) 짧은 트랜잭션 — 결과 저장 + ANALYZED 전이
            result = analysisTransactionService.saveResult(
                    visitId, prediction.modelVersionOrDefault(), prediction.top1().diseaseCode(),
                    prediction.top1().confidence(), topK, inferenceMs, heatmapKey);
        } catch (RuntimeException e) {
            analysisTransactionService.abortAnalysis(visitId, target.previousStatus());
            throw e;
        }

        log.info("분석 완료 visitId={} top1={} confidence={}", visitId,
                prediction.top1().diseaseCode(), prediction.top1().confidence());

        return toResponse(result, prediction);
    }

    /** 히트맵 저장 (키만 DB에 보관, URL은 응답 시 생성). 스토리지 왕복이므로 트랜잭션 밖에서 부른다. */
    private String uploadHeatmap(Long visitId, FastApiPredictResponse prediction) {
        if (prediction.heatmapBase64() == null) return null;
        byte[] heatmapBytes = Base64.getDecoder().decode(prediction.heatmapBase64());
        String key = "heatmap/" + visitId + "/" + System.currentTimeMillis() + ".jpg";
        return imageStorageService.uploadBytes(key, heatmapBytes, "image/jpeg");
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
                result.getAnalyzedAt(),
                heatmapApiUrl(result)
        );
    }

    public org.springframework.http.ResponseEntity<byte[]> getHeatmapContent(Long visitId) {
        AnalysisResult result = analysisResultRepository
                .findFirstByVisitIdOrderByAnalyzedAtDesc(visitId)
                .filter(r -> r.getHeatmapImageUrl() != null)
                .orElseThrow(() -> new NoSuchElementException("히트맵이 없습니다: " + visitId));
        byte[] bytes = imageStorageService.download(result.getHeatmapImageUrl());
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "image/jpeg")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "max-age=31536000, immutable")
                .body(bytes);
    }

    private FastApiPredictResponse callFastApi(String imageUrl) {
        try {
            byte[] imageBytes = imageStorageService.download(imageUrl);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String requestBody = objectMapper.writeValueAsString(Map.of("image_base64", base64Image));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fastapiUrl + "/predict-base64"))
                    .header("Content-Type", "application/json")
                    // FastAPI 에는 로그인이 없다. 같은 네트워크 안의 아무나 추론을 부르지 못하도록
                    // 백엔드와 FastAPI 만 아는 값을 함께 보낸다(FastAPI verify_internal_secret).
                    .header("X-Internal-Secret", fastapiInternalSecret)
                    // 이 한 줄이 없으면 무한 대기다. 상대가 죽지 않고 멈추기만 해도 요청 스레드가 영영 묶인다.
                    .timeout(Duration.ofSeconds(fastapiTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("FastAPI 오류 " + response.statusCode() + ": " + response.body());
            }

            return objectMapper.readValue(response.body(), FastApiPredictResponse.class);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            log.error("FastAPI 연결 실패 url={}", fastapiUrl, e);
            throw new AiServiceUnavailableException("AI 분석 서버에 연결할 수 없습니다. 서버 상태를 확인해주세요.");
        } catch (HttpTimeoutException e) {
            log.error("FastAPI 응답 시간 초과 url={} timeout={}s", fastapiUrl, fastapiTimeoutSeconds);
            throw new AiServiceUnavailableException(
                    "AI 분석 서버가 " + fastapiTimeoutSeconds + "초 안에 응답하지 않았습니다. 잠시 후 다시 시도해주세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceUnavailableException("AI 분석 요청이 중단되었습니다.");
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
                result.getAnalyzedAt(),
                heatmapApiUrl(result)
        );
    }

    private static String heatmapApiUrl(AnalysisResult result) {
        if (result.getHeatmapImageUrl() == null) return null;
        return "/api/v1/visits/" + result.getVisitId() + "/analysis/heatmap";
    }

    // FastAPI 응답 구조 (snake_case → camelCase 매핑)
    private record FastApiPredictResponse(
            @JsonProperty("is_valid") Boolean isValid,
            String message,
            Double threshold,
            FastApiTop1 top1,
            List<FastApiTop5Item> top5,
            @JsonProperty("heatmap_base64") String heatmapBase64,
            @JsonProperty("model_version") String modelVersion
    ) {
        private boolean isValidOrDefault() {
            return isValid == null || isValid;
        }

        private String messageOrDefault() {
            return message != null && !message.isBlank()
                    ? message
                    : "AI 분석에 적합하지 않은 이미지입니다.";
        }

        /** model_version 컬럼은 NOT NULL 이라, 구버전 FastAPI가 필드를 안 보내는 과도기에도 저장이 깨지면 안 된다. */
        private String modelVersionOrDefault() {
            return modelVersion != null && !modelVersion.isBlank()
                    ? modelVersion
                    : "unknown";
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
