package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.image.ImageStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
import java.util.ArrayList;
import java.util.Base64;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisTransactionService analysisTransactionService;
    private final AnalysisResultRepository analysisResultRepository;
    private final AnalysisImageRepository analysisImageRepository;
    private final DiseaseRepository diseaseRepository;
    private final ImageStorageService imageStorageService;
    private final LowConfidenceCaution lowConfidenceCaution;
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

    /**
     * 모델 클래스 코드 → 한글 병명. 두 태스크(skin_lesion 8종 + facial 6종)의 합집합이다.
     * 한글 병명의 원본은 docs/aihub-datasets-plan.md §3-1 표이며, fastapi/main.py 의 names_ko·DB 시드·
     * KioskService 의 같은 상수와 글자 하나까지 같아야 한다(fastapi/tests/test_model_contract.py [6][7]).
     * disease.name_ko 를 읽지 않고 여기 두는 이유는 KioskService.DISEASE_NAME_KO 주석에.
     *
     * Map.of 가 아니라 Map.ofEntries 인 이유: Map.of 오버로드는 10쌍까지라 14쌍이 들어가지 않는다.
     */
    private static final Map<String, String> DISEASE_NAME_KO = Map.ofEntries(
            // skin_lesion — HAM10000 7종 + SCIN 염증성 1종 (fastapi/model.pth)
            Map.entry("akiec", "광선각화증/상피내암"),
            Map.entry("bcc", "기저세포암"),
            Map.entry("bkl", "양성 각화증성 병변"),
            Map.entry("df", "피부섬유종"),
            Map.entry("mel", "악성 흑색종"),
            Map.entry("nv", "멜라닌세포모반"),
            Map.entry("vasc", "혈관성 병변"),
            Map.entry("inflammatory", "염증성 피부질환"),
            // facial — 안면부 피부질환 6종 (fastapi/models/facial.pth, AI허브 71863)
            Map.entry("acne", "여드름"),
            Map.entry("atopic", "아토피피부염"),
            Map.entry("normal", "정상 피부"),
            Map.entry("psoriasis", "건선"),
            Map.entry("rosacea", "주사(장미증)"),
            Map.entry("seb_derm", "지루피부염")
    );

    /**
     * Top-K 각 후보에 붙는 소견 문구. 화면의 참고용 설명이지 진단 근거가 아니다.
     *
     * facial 6종의 문구는 합성 데이터(AI허브 71863)로 학습한 참고용 모델이라는 전제로 썼다 — 병변의
     * 전형적 임상 소견만 짧게 적고 치료 지시나 단정은 넣지 않는다. 정상 피부는 '병변 소견이 뚜렷하지
     * 않다'는 뜻이지 '이상 없음'의 보증이 아니므로, 증상이 있으면 진료를 권하는 문장을 함께 둔다.
     */
    private static final Map<String, String> DISEASE_REASON = Map.ofEntries(
            // skin_lesion
            Map.entry("nv", "균일한 색소 분포와 규칙적인 경계선이 멜라닌세포모반의 특성과 일치합니다. 대부분 양성이나 크기 변화 시 추적 관찰이 필요합니다."),
            Map.entry("mel", "불규칙한 색상 분포와 비대칭적 경계 패턴이 악성 흑색종의 특성과 일치합니다."),
            Map.entry("bkl", "각질층의 과증식 및 지루성 표면 패턴이 관찰됩니다. 양성 경과가 많으나 급격한 변화 시 조직 검사를 권장합니다."),
            Map.entry("bcc", "진주빛 경계와 확장된 모세혈관 패턴이 기저세포암의 특성과 일치합니다. 조기 외과적 제거가 권장됩니다."),
            Map.entry("akiec", "표피 세포의 이형성 패턴이 광선각화증/상피내암의 특성과 일치합니다. 전암성 병변으로 조기 치료가 중요합니다."),
            Map.entry("df", "피부 내 경계 명확한 섬유성 결절 패턴이 피부섬유종의 특성과 일치합니다. 양성이며 경과 관찰만으로 충분합니다."),
            Map.entry("vasc", "혈관 확장 및 혈관 내 이상 패턴이 감지되었습니다. 혈관 레이저 치료를 고려할 수 있습니다."),
            Map.entry("inflammatory", "홍반·부종·인설을 동반한 염증성 반응 패턴이 관찰됩니다. 습진·접촉피부염·두드러기·벌레물림 등이 포함되는 군으로, 색소성 종양 계열과는 구분됩니다. 원인 물질 회피와 국소 치료로 호전되는 경우가 많으나 증상이 지속되면 피부과 진료가 필요합니다."),
            // facial
            Map.entry("acne", "면포·구진·농포 등 모낭 단위의 염증 소견이 여드름의 특성과 일치합니다. 병변 분포와 경과를 함께 확인해 주세요."),
            Map.entry("atopic", "홍반과 건조한 인설, 긁은 자국을 동반한 습진성 패턴이 아토피피부염의 특성과 일치합니다. 가려움의 정도와 재발 병력을 함께 확인해 주세요."),
            Map.entry("normal", "병변 소견이 뚜렷하지 않습니다. 모델이 병변을 놓쳤을 수 있으므로 증상이 있으면 진료가 필요합니다."),
            Map.entry("psoriasis", "경계가 명확한 홍반 위에 은백색 인설이 덮인 판 모양 패턴이 건선의 특성과 일치합니다. 두피·팔꿈치·무릎 등 호발 부위와 손발톱 변화를 함께 확인해 주세요."),
            Map.entry("rosacea", "얼굴 중앙부의 지속적인 홍조와 확장된 모세혈관, 구진 패턴이 주사(장미증)의 특성과 일치합니다. 면포가 없다는 점에서 여드름과 구분되며 열·음주·햇빛 등 악화 요인 확인이 필요합니다."),
            Map.entry("seb_derm", "피지 분비가 많은 부위의 홍반과 기름진 노란 인설이 지루피부염의 특성과 일치합니다. 만성 재발 경과가 흔하며 건선·아토피피부염과의 감별이 필요합니다.")
    );

    /**
     * 기존 2-인자 진입점 — 기본 태스크(skin_lesion)로 위임한다.
     * analysisType 이 생기기 전의 호출자와 {@code AnalysisResilienceTest} 가 그대로 돈다.
     */
    public AnalysisResponse analyze(Long visitId, List<Long> imageIds) {
        return analyze(visitId, imageIds, AnalysisType.DEFAULT);
    }

    /**
     * AI 분석 요청.
     *
     * 이 메서드에는 의도적으로 {@code @Transactional} 이 없다.
     * FastAPI 추론과 히트맵 업로드는 수 초가 걸리는 외부 호출인데, 트랜잭션 안에서 부르면
     * 그동안 DB 커넥션을 하나씩 붙잡고 있게 된다. 커넥션 풀(기본 10개)이 마르면 분석과 무관한
     * 접수·조회 화면까지 통째로 멈춘다 — 진료실에서는 "서버가 죽었다"로 보인다.
     * DB 작업은 {@link AnalysisTransactionService} 의 짧은 트랜잭션으로 나눠 처리한다.
     *
     * 대신 트랜잭션이 실패를 자동으로 되돌려 주지 않으므로, 외부 호출이 실패하면
     * ANALYZING 상태를 직접 복구해야 한다(아래 catch 절).
     *
     * @param analysisType 어느 모델로 볼지. null 이면 {@link AnalysisType#DEFAULT}(skin_lesion) —
     *                     이 인자가 생기기 전의 호출자와 같은 동작이다.
     */
    public AnalysisResponse analyze(Long visitId, List<Long> imageIds, AnalysisType analysisType) {
        AnalysisType task = AnalysisType.orDefault(analysisType);

        // 컨트롤러의 @Valid(@NotEmpty)가 HTTP 경로는 이미 막지만, 서비스를 직접 부르는 경로
        // (배치·테스트·향후 내부 호출)에서는 get(0)이 그대로 500으로 터진다.
        if (imageIds == null || imageIds.isEmpty()) {
            throw new IllegalArgumentException("분석할 이미지를 선택해주세요.");
        }

        // 화면에서 고른 것 중 **모델에 실제로 넣을 것**만 추린다 (아래 메서드 주석 참고)
        List<Long> targetImageIds = selectInferenceTargets(imageIds);

        // (1) 짧은 트랜잭션 — 분석 시작 표시 + 대상 이미지 경로 조회 + 소유 검사
        AnalysisTransactionService.AnalysisTarget target =
                analysisTransactionService.beginAnalysis(visitId, targetImageIds);

        AnalysisResult result;
        FastApiPredictResponse prediction;
        try {
            // (2) 트랜잭션 밖 — 여기서 몇 초가 걸려도 DB 커넥션은 하나도 잡혀 있지 않다
            long startMs = System.currentTimeMillis();
            // 현재 FastAPI /predict-base64 는 이미지 1장을 받는다(fastapi/main.py PredictRequest).
            // 다중 입력 모델이 되면 target.imageUrls() 를 그대로 넘기도록 여기만 바꾼다.
            prediction = callFastApi(target.primaryImageUrl(), task);
            int inferenceMs = (int) (System.currentTimeMillis() - startMs);

            // 응답의 task 로 "요청한 모델이 실제로 돌았는지" 확인한다. task 를 모르는 구버전 FastAPI 는
            // 이 필드를 안 보내고 기본 모델로 돌리므로, facial 을 요청했는데 skin_lesion 코드가 돌아와
            // facial 로 저장되는 사고를 여기서 막는다 — 그 행은 top_k 의 코드가 다른 클래스 목록을
            // 가리키는 거짓 기록이 된다. 기본 태스크 요청에 task 가 비어 오는 것만 과도기 호환으로 허용한다.
            String servedTask = prediction.task();
            if (servedTask == null ? task != AnalysisType.DEFAULT : !servedTask.equals(task.code())) {
                log.error("FastAPI 가 요청한 태스크로 추론하지 않았다 requested={} served={}", task.code(), servedTask);
                throw new AiServiceUnavailableException(
                        "AI 분석 서버가 요청한 분석 종류(" + task.code() + ")를 지원하지 않습니다. 서버 버전을 확인해주세요.");
            }

            // 신뢰도가 낮아도 여기서 막지 않는다. 결과는 그대로 내보내고 "확신 낮음" 등급만 붙인다.
            // 차단하던 시절에는 홀드아웃 2,857장 중 4.5% 가 답을 못 받았고, 하필 그 안에
            // 흑색종이 몰려 있어 재현율이 89.3% → 88.8% 로 깎였다. 근거: LowConfidenceCaution.
            String confidenceLevel = lowConfidenceCaution.normalizeLevel(prediction.confidenceLevel());

            List<TopKItem> topK = prediction.top5().stream()
                    .map(r -> new TopKItem(r.diseaseCode(), r.confidence()))
                    .toList();

            AnalysisTransactionService.StoredHeatmap heatmap = uploadHeatmaps(visitId, prediction);

            // 확대 재판정을 했으면 원본만 본 1순위를 함께 남긴다 — top1 이 그것과 다를 수 있다
            // ("원본만 보면 X 였는데 확대 영역까지 보고 Y 로 판정했다"를 나중에도 말할 수 있어야 한다).
            FastApiTop1 fullImage = prediction.fullImageTop1();
            TopKItem fullImageTop1 = fullImage == null ? null
                    : new TopKItem(fullImage.diseaseCode(), fullImage.confidence());

            // (3) 짧은 트랜잭션 — 결과 저장 + 근거 이미지 매핑 + ANALYZED 전이
            result = analysisTransactionService.saveResult(
                    visitId, targetImageIds, task,
                    prediction.modelVersionOrDefault(), prediction.top1().diseaseCode(),
                    prediction.top1().confidence(), confidenceLevel, topK, inferenceMs,
                    heatmap, fullImageTop1);
        } catch (RuntimeException e) {
            analysisTransactionService.abortAnalysis(visitId, target.previousStatus());
            throw e;
        }

        log.info("분석 완료 visitId={} task={} top1={} confidence={}", visitId, task.code(),
                prediction.top1().diseaseCode(), prediction.top1().confidence());

        return toResponse(result, prediction, targetImageIds);
    }

    /**
     * 화면에서 고른 이미지들 중 모델에 실제로 넣을 것을 고른다.
     *
     * 모델을 확장할 때 고쳐야 하는 곳은 여기 한 군데다. 지금 FastAPI 는 이미지 1장만
     * 받으므로 첫 장만 고른다. 다중 입력 모델로 바뀌면 이 메서드가 {@code imageIds} 를 그대로
     * 돌려주기만 하면 되고, {@code beginAnalysis → callFastApi → saveResult} 는 이미 리스트를
     * 받도록 되어 있어 {@code analysis_image} 에 N행이 자동으로 쌓인다.
     * 테이블의 복합 PK {@code (analysis_id, image_id)} 가 처음부터 그걸 상정하고 만들어졌다.
     *
     * 고르지 않은 이미지는 매핑에 남기지 않는다. 테이블 코멘트가 '분석에 사용된 이미지'이기
     * 때문이다. 모델이 보지도 않은 사진을 근거로 적어 두면 나중에 "이 진단은 무엇을 보고 내렸나"에
     * 거짓으로 답하게 되고, 그 답은 처방({@code prescription.analysis_id})과
     * 제증명({@code certificate})까지 그대로 흘러간다.
     *
     * 알려진 UX 간극. 진료 화면은 사진을 여러 장 선택할 수 있는데(Clinic.tsx
     * {@code toggleSelectedImage}) 실제로는 첫 장만 분석된다. 선택 UI 를 1장으로 제한하거나
     * 다중 입력 모델을 도입하기 전까지는 아래 경고 로그가 그 간극을 드러내는 유일한 신호다.
     */
    private List<Long> selectInferenceTargets(List<Long> imageIds) {
        if (imageIds.size() > 1) {
            log.warn("이미지 {}장이 선택됐지만 현재 모델은 1장만 분석한다. 사용={} 무시={}",
                    imageIds.size(), imageIds.get(0), imageIds.subList(1, imageIds.size()));
        }
        return List.of(imageIds.get(0));
    }

    /**
     * 이 분석이 근거로 삼은 이미지 ID 목록.
     *
     * 2026-08-30 이전에 만들어진 분석은 매핑이 없어 빈 목록이 나온다.
     * 그 시절 기록에는 근거 이미지가 실제로 존재하지 않으므로, 추측해서 채우지 않고 비워 둔다.
     */
    private List<Long> findAnalyzedImageIds(Long analysisId) {
        return analysisImageRepository.findByAnalysisIdOrderByImageIdAsc(analysisId).stream()
                .map(AnalysisImage::getImageId)
                .toList();
    }

    /**
     * 히트맵 저장 (키만 DB에 보관, URL은 응답 시 생성). 스토리지 왕복이므로 트랜잭션 밖에서 부른다.
     *
     * 확대 히트맵은 하나가 실패하면 그것만 건너뛴다. 확대 보기는 전체 히트맵을 거들 뿐이라 그 때문에
     * 이미 끝난 추론까지 버리면 안 된다. 전체 히트맵은 지금까지처럼 실패하면 분석이 실패한다.
     */
    private AnalysisTransactionService.StoredHeatmap uploadHeatmaps(Long visitId, FastApiPredictResponse prediction) {
        // 전체·확대 히트맵이 같은 접두어를 쓴다 — 스토리지에서 한 분석의 그림끼리 나란히 보인다.
        String prefix = "heatmap/" + visitId + "/" + System.currentTimeMillis();
        String key = prediction.heatmapBase64() == null ? null
                : imageStorageService.uploadBytes(prefix + ".jpg",
                        Base64.getDecoder().decode(prediction.heatmapBase64()), "image/jpeg");

        List<HeatmapCrop> crops = new ArrayList<>();
        for (FastApiCrop crop : Objects.requireNonNullElse(prediction.crops(), List.<FastApiCrop>of())) {
            try {
                String cropKey = imageStorageService.uploadBytes(prefix + "_crop" + crop.rank() + ".jpg",
                        Base64.getDecoder().decode(crop.heatmapBase64()), "image/jpeg");
                crops.add(new HeatmapCrop(crop.rank(), cropKey, crop.boxNorm(), crop.peak(), crop.source()));
            } catch (RuntimeException e) {
                log.warn("확대 히트맵 저장 실패 — 이 영역만 건너뛴다 visitId={} rank={}", visitId, crop.rank(), e);
            }
        }
        return new AnalysisTransactionService.StoredHeatmap(key, prediction.heatmapStyle(), List.copyOf(crops));
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
                findAnalyzedImageIds(result.getId()),
                result.getModelVersion(),
                result.getAnalysisType(),
                new AnalysisResponse.Top1Result(
                        disease.getDiseaseCode(),
                        DISEASE_NAME_KO.getOrDefault(disease.getDiseaseCode(), disease.getDiseaseCode()),
                        result.getConfidence()
                ),
                top5,
                result.getConfidenceLevel(),
                lowConfidenceCaution.messageFor(result.getConfidenceLevel(), disease.getDiseaseCode(), fullImageCode(result)),
                result.getInferenceTimeMs(),
                result.getAnalyzedAt(),
                heatmapApiUrl(result),
                result.getHeatmapStyle(),
                heatmapCropResults(result),
                fullImageTop1Result(result)
        );
    }

    public ResponseEntity<byte[]> getHeatmapContent(Long visitId) {
        AnalysisResult result = analysisResultRepository
                .findFirstByVisitIdOrderByAnalyzedAtDesc(visitId)
                .filter(r -> r.getHeatmapImageUrl() != null)
                .orElseThrow(() -> new NoSuchElementException("히트맵이 없습니다: " + visitId));
        return jpeg(imageStorageService.download(result.getHeatmapImageUrl()));
    }

    /** 최근 분석의 확대 히트맵 하나. 번호는 {@link AnalysisResponse.HeatmapCropResult#rank()} 다. */
    public ResponseEntity<byte[]> getHeatmapCropContent(Long visitId, int rank) {
        HeatmapCrop crop = analysisResultRepository
                .findFirstByVisitIdOrderByAnalyzedAtDesc(visitId)
                .map(AnalysisResult::getHeatmapCrops)
                .flatMap(crops -> crops.stream().filter(c -> c.rank() == rank).findFirst())
                .orElseThrow(() -> new NoSuchElementException("확대 히트맵이 없습니다: " + visitId + " #" + rank));
        return jpeg(imageStorageService.download(crop.key()));
    }

    private static ResponseEntity<byte[]> jpeg(byte[] bytes) {
        // 환자 병변 사진이라 중간 프록시 등 공유 캐시에 담기면 안 된다 — private로 제한.
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(bytes);
    }

    private FastApiPredictResponse callFastApi(String imageUrl, AnalysisType task) {
        try {
            byte[] imageBytes = imageStorageService.download(imageUrl);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            // task 는 FastAPI 가 모델을 고르는 키다(fastapi/main.py TASKS). 생략해도 그쪽이 skin_lesion 으로
            // 접지만, 기본값을 두 서버에 따로 두면 언젠가 한쪽만 바뀐다 — 항상 명시해서 보낸다.
            // task 필드가 없던 구버전 FastAPI 는 모르는 필드를 무시하므로(pydantic 기본값) 배포 순서와 무관하다.
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "image_base64", base64Image,
                    "task", task.code()));

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

            if (response.statusCode() == 503) {
                // 등록은 됐지만 가중치 파일이 없어 로드되지 않은 태스크가 이렇게 온다(fastapi/main.py —
                // CI·개발 환경에는 facial.pth 가 없다). 우리 버그가 아니라 그쪽 준비 상태의 문제이므로
                // 500 이 아니라 503 으로 내보낸다. 로그에 FastAPI 본문을 남겨 어느 태스크가 빠졌는지 보이게 한다.
                log.error("FastAPI 503 task={} body={}", task.code(), response.body());
                throw new AiServiceUnavailableException(
                        "AI 분석 서버에 '" + task.code() + "' 모델이 준비되어 있지 않습니다. 서버 상태를 확인해주세요.");
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("FastAPI 오류 " + response.statusCode() + ": " + response.body());
            }

            return objectMapper.readValue(response.body(), FastApiPredictResponse.class);
        } catch (AiServiceUnavailableException e) {
            // 바로 위에서 던진 것. 아래 Exception 절에 잡혀 RuntimeException(500) 으로 둔갑하지 않게 그대로 올린다.
            throw e;
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

    private AnalysisResponse toResponse(AnalysisResult result, FastApiPredictResponse prediction,
                                        List<Long> analyzedImageIds) {
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
                analyzedImageIds,
                result.getModelVersion(),
                result.getAnalysisType(),
                new AnalysisResponse.Top1Result(
                        prediction.top1().diseaseCode(),
                        DISEASE_NAME_KO.getOrDefault(prediction.top1().diseaseCode(), prediction.top1().diseaseCode()),
                        result.getConfidence()
                ),
                top5,
                result.getConfidenceLevel(),
                lowConfidenceCaution.messageFor(result.getConfidenceLevel(), prediction.top1().diseaseCode(), fullImageCode(result)),
                result.getInferenceTimeMs(),
                result.getAnalyzedAt(),
                heatmapApiUrl(result),
                result.getHeatmapStyle(),
                heatmapCropResults(result),
                fullImageTop1Result(result)
        );
    }

    private static String heatmapApiUrl(AnalysisResult result) {
        if (result.getHeatmapImageUrl() == null) return null;
        return "/api/v1/visits/" + result.getVisitId() + "/analysis/heatmap";
    }

    /** 저장된 확대 히트맵을 화면용으로. 이 기능 전의 분석·확대 보기가 없는 분석은 빈 목록이다. */
    private static List<AnalysisResponse.HeatmapCropResult> heatmapCropResults(AnalysisResult result) {
        if (result.getHeatmapCrops() == null) return List.of();
        return result.getHeatmapCrops().stream()
                .map(c -> new AnalysisResponse.HeatmapCropResult(
                        c.rank(),
                        "/api/v1/visits/" + result.getVisitId() + "/analysis/heatmap/crops/" + c.rank(),
                        c.boxNorm(),
                        c.peak(),
                        c.source()))
                .toList();
    }

    /** 재판정 전 원본 사진의 1순위 코드 — 경고 문구의 강도를 가를 때 함께 본다. 재판정하지 않았으면 null. */
    private static String fullImageCode(AnalysisResult result) {
        return result.getFullImageTop1() == null ? null : result.getFullImageTop1().code();
    }

    private static AnalysisResponse.FullImageTop1 fullImageTop1Result(AnalysisResult result) {
        TopKItem item = result.getFullImageTop1();
        if (item == null) return null;
        return new AnalysisResponse.FullImageTop1(
                item.code(), DISEASE_NAME_KO.getOrDefault(item.code(), item.code()), item.confidence());
    }
}

