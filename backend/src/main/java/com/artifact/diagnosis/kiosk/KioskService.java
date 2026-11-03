package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.AiServiceUnavailableException;
import com.artifact.diagnosis.analysis.AnalysisResponse;
import com.artifact.diagnosis.analysis.AnalysisType;
import com.artifact.diagnosis.analysis.FastApiPredictResponse;
import com.artifact.diagnosis.analysis.FastApiTop1;
import com.artifact.diagnosis.analysis.HeatmapCrop;
import com.artifact.diagnosis.analysis.HeatmapStorage;
import com.artifact.diagnosis.analysis.LowConfidenceCaution;
import com.artifact.diagnosis.analysis.StoredHeatmap;
import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.image.ImageStorageService;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.prescription.AiCommentService;
import com.artifact.diagnosis.visit.KioskTokenGenerator;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대기실 키오스크 예비분석 서비스.
 * Visit의 정식 진료 FSM과는 분리된 사이드 채널 — 여기서 만든 결과는 visit.status에 영향을 주지 않는다.
 *
 * 분석은 동기로 처리한다(태블릿이 결과를 기다리는 화면이라 큐로 미룰 이점이 적다). 대신 오래 걸리는
 * 참고 소견(LLM)은 분석 응답에서 떼어 냈다 — 화면이 결과를 먼저 그리고 {@link #generateComment} 를 따로 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KioskService {

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final PreliminaryAnalysisRepository preliminaryAnalysisRepository;
    private final KioskTransactionService kioskTransactionService;
    private final ImageStorageService imageStorageService;
    private final AiCommentService aiCommentService;
    private final ObjectMapper objectMapper;
    private final KioskTokenGenerator kioskTokenGenerator;
    private final LowConfidenceCaution lowConfidenceCaution;
    private final HeatmapStorage heatmapStorage;

    /** 요청마다 새로 만들지 않고 공유한다 — {@code HttpClientConfig} 참고. 연결 타임아웃이 걸려 있다. */
    private final HttpClient httpClient;

    @Value("${fastapi.url:http://localhost:8000}")
    private String fastapiUrl;

    /** 추론 응답 대기 한도. AnalysisService와 같은 값을 쓴다 — 같은 서버, 같은 모델이다. */
    @Value("${fastapi.timeout-seconds:30}")
    private long fastapiTimeoutSeconds;

    /** FastAPI 와 공유하는 내부 호출 시크릿. AnalysisService 와 같은 값. */
    @Value("${fastapi.internal-secret}")
    private String fastapiInternalSecret;

    /**
     * QR 없이 자동 진입하는 폴백(/api/kiosk/pending)의 활성화 여부. 기본은 꺼짐.
     * 이 엔드포인트만 유일하게 토큰 없이 접근 대상을 알려주므로 — 즉 아무나 호출해 다음 대기 환자의
     * 토큰을 받아갈 수 있으므로 — 시연에서 QR을 못 쓸 때만 KIOSK_AUTO_PENDING=true 로 연다.
     */
    @Value("${kiosk.auto-pending.enabled:false}")
    private boolean autoPendingEnabled;

    /**
     * disease.name_ko 컬럼은 과거 초기 적재 시 인코딩이 깨져 있어(mojibake) 재조회 시 사용하지 않는다.
     * AnalysisService.DISEASE_NAME_KO 와 동일한 값 — 모델 클래스 코드 → 한글 병명 하드코딩 매핑.
     * 두 사본의 코드 집합과 값이 같은지는 fastapi/tests/test_model_contract.py [7] 이 대조한다.
     * 키오스크 참고 소견 프롬프트에도 이 이름을 넘긴다 — 코드(nv, mel)를 그대로 주면 환자 화면에 영문 약어가 섞인다.
     * 키오스크도 두 모델을 다 쓴다(방문자가 촬영 대상을 고른다) — skin_lesion 8종과 facial 6종, 14쌍이다.
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
     * 지금 분석 중인 접수들. 같은 접수(토큰)의 분석이 겹치면 두 번째를 409 로 돌려보낸다.
     *
     * 겹치는 경우는 생각보다 흔하다 — 두 기기가 같은 QR 을 찍었을 때, 응답이 늦어 화면이 오류를 띄운 사이
     * 환자가 다시 눌렀을 때(서버는 첫 요청을 아직 처리 중이다). 겹치게 두면 두 가지가 깨진다.
     *   - 이 접수의 첫 저장이 둘 다 INSERT 를 시도해 visit_id UNIQUE 에 걸린 쪽이 500 으로 끝난다.
     *   - 이미 행이 있으면 나중에 끝난 쪽이 통째로 덮어써서, 먼저 끝난 기기는 자기 병명 옆에 다른 사진의
     *     히트맵을 받는다(히트맵 경로가 "그 접수의 지금 행"을 읽기 때문이다).
     * 백엔드는 한 대라 메모리의 집합으로 충분하다. 백엔드를 여러 대로 늘리면 공유 저장소(Redis 등)로 옮겨야 한다.
     */
    private final Set<Long> analyzingVisits = ConcurrentHashMap.newKeySet();

    /** 참고 소견을 만드는 중인 접수들. 같은 소견을 위해 유료 LLM 을 두 번 부르지 않는다. */
    private final Set<Long> commentingVisits = ConcurrentHashMap.newKeySet();

    /**
     * QR 없이 자동 진입하는 폴백(/kiosk?auto=1)용 — 가장 최근 RECEIVED 상태이면서 예비분석이 없는 Visit 1건.
     * 전역으로 "다음 환자 1명"을 정하는 방식이라 동시 1명만 가능하다. 여러 환자를 동시에 진행하려면
     * 접수 화면의 QR(=토큰별 경로)을 써야 한다.
     *
     * 기본적으로 꺼져 있다. 인증도 토큰도 없이 호출되는 유일한 경로라, 켜두면 누구나
     * 다음 대기 환자의 키오스크 토큰을 받아 그 환자의 세션·히트맵에 접근할 수 있다.
     * 환자 실명은 응답에서 제외한다 — 태블릿은 토큰으로 이동한 뒤 세션 조회에서 이름을 받는다.
     *
     * 대상 선정은 {@code VisitRepository.findLatestWithoutPreliminaryAnalysis} 한 번으로 끝낸다.
     * "최신순 1건"인 이유는 그 쿼리 주석 참고 — 오래된 순으로 고르면 태블릿이 묵은 접수에 갇힌다.
     */
    @Transactional
    public KioskPendingResponse findPending() {
        if (!autoPendingEnabled) {
            throw new NoSuchElementException("자동 진입이 비활성화되어 있습니다. QR 코드를 사용하세요.");
        }

        Visit pendingVisit = visitRepository
                .findLatestWithoutPreliminaryAnalysis(VisitStatus.RECEIVED, Limit.of(1))
                .stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("예비분석이 필요한 대기 환자가 없습니다."));

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
     * Top-K + GradCAM 을 FastAPI 에서 받아 preliminary_analysis 에 저장(upsert)한다. 참고 소견은 여기서 만들지
     * 않는다 — LLM 은 재시도·대체 모델까지 가면 수십 초가 걸리는데, 결과와 히트맵이 이미 손에 있는데도 환자가
     * 그만큼 더 로딩 화면을 본다. 화면이 결과를 먼저 그리고 {@link #generateComment} 를 따로 부른다.
     * Visit 상태는 변경하지 않는다(RECEIVED 유지) — 정식 진료 흐름과 완전히 분리된 채널이다.
     *
     * task 는 방문자가 촬영 전에 고른 촬영 대상이다(얼굴 → facial, 점·피부 병변 → skin_lesion).
     *
     * visitId가 아니라 토큰을 받는 이유: 이 엔드포인트는 인증이 없어서, visitId(순차 정수)를 그대로
     * 받으면 같은 LAN의 누구나 임의 환자의 예비분석을 덮어쓸 수 있다.
     *
     * 같은 접수의 분석이 이미 돌고 있으면 409 다({@link #analyzingVisits} 주석).
     *
     * {@code @Transactional} 이 없는 것은 의도된 것이다. 한 번 호출에 외부 왕복이
     * 두 번(FastAPI 추론 → 히트맵 업로드) 들어가는데, 트랜잭션으로 감싸면 그 수 초 동안
     * DB 커넥션을 붙잡는다. 태블릿 몇 대가 동시에 촬영하면 커넥션 풀이 말라 진료실 화면까지 멈춘다.
     * DB 쓰기는 마지막에 {@link KioskTransactionService#saveResult} 한 번으로 끝낸다.
     */
    public PreliminaryAnalysisResponse analyze(String token, MultipartFile file, AnalysisType task) {
        Long visitId = resolveToken(token).getId();

        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("이미지 파일을 읽을 수 없습니다.");
        }

        if (!analyzingVisits.add(visitId)) {
            throw new IllegalStateException("이 접수는 지금 다른 화면에서 분석 중입니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            FastApiPredictResponse prediction = callFastApi(imageBytes, task);

            // 신뢰도가 낮아도 막지 않는다 — 결과를 그대로 주고 "확신 낮음" 등급만 붙인다.
            // 이유와 실측 근거는 LowConfidenceCaution / fastapi/main.py 주석 참고.
            String confidenceLevel = lowConfidenceCaution.normalizeLevel(prediction.confidenceLevel());

            List<TopKItem> topK = prediction.top5().stream()
                    .map(r -> new TopKItem(r.diseaseCode(), r.confidence()))
                    .toList();

            // 전체·확대 히트맵을 같은 접두어로 올린다. 진료실 분석과 같은 부품이다 — 확대 히트맵 하나가
            // 실패하면 그것만 건너뛰는 규칙이 두 화면에서 갈라지지 않는다(HeatmapStorage).
            // 같은 접수의 분석은 겹치지 않으므로(위 409) 밀리초까지 같은 키가 만들어질 일이 없다.
            StoredHeatmap heatmap = heatmapStorage.upload(
                    "heatmap/preliminary/" + visitId + "/" + System.currentTimeMillis(), prediction);
            // 확대 재판정을 했으면 원본만 본 1순위도 남긴다 — 태블릿과 의사 화면이 같은 사실을 보여 줘야 한다.
            FastApiTop1 fullImage = prediction.fullImageTop1();
            TopKItem fullImageTop1 = fullImage == null ? null : new TopKItem(fullImage.diseaseCode(), fullImage.confidence());

            // 여기까지가 외부 호출 구간. DB는 아래 한 줄(짧은 트랜잭션)에서만 만진다.
            PreliminaryAnalysis entity = kioskTransactionService.saveResult(
                    visitId, task, topK, confidenceLevel, heatmap, fullImageTop1, prediction.modelVersionOrDefault());

            log.info("예비분석 완료 visitId={} task={} top1={}", visitId, task.code(), prediction.top1().diseaseCode());

            return toResponse(token, entity, prediction);
        } finally {
            analyzingVisits.remove(visitId);
        }
    }

    /**
     * 직전 예비분석에 대한 참고 소견을 만들어 저장하고 돌려준다. 태블릿이 결과를 그린 뒤 따로 부른다.
     *
     * 이미 소견이 있으면 LLM 을 부르지 않고 그대로 돌려준다 — 화면 재진입·두 번 누르기로 유료 호출을 반복하지 않는다.
     * 생성이 실패해도 예외가 아니라 안내 문구가 돌아오고(AiCommentService), 그 문구도 소견으로 저장된다.
     * 다시 만들려면 사진을 다시 찍으면 된다(재분석이 소견을 비운다).
     *
     * 소견을 만드는 동안 재촬영이 끝나면 들고 있는 소견은 이전 사진의 것이다 — 저장하지 않고, 응답만 준다
     * ({@link KioskTransactionService#saveAiComment}). 그 화면은 곧 새 결과로 바뀐다.
     *
     * {@code @Transactional} 이 없는 것은 {@link #analyze} 와 같은 이유다 — LLM 왕복 동안 DB 커넥션을 쥐지 않는다.
     */
    public KioskCommentResponse generateComment(String token) {
        Long visitId = resolveToken(token).getId();
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .orElseThrow(() -> new NoSuchElementException("예비분석 결과가 없습니다. 먼저 분석을 실행하세요."));
        if (entity.getAiComment() != null) {
            return new KioskCommentResponse(entity.getAiComment());
        }
        if (!commentingVisits.add(visitId)) {
            throw new IllegalStateException("참고 소견을 만드는 중입니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            String heatmapKey = entity.getGradcamUrl();
            List<TopKItem> topK = entity.getTopKJson() == null ? List.of() : entity.getTopKJson();
            String aiComment = aiCommentService.generatePreliminaryComment(topK, DISEASE_NAME_KO);
            if (!kioskTransactionService.saveAiComment(visitId, heatmapKey, aiComment)) {
                log.info("참고 소견 저장 건너뜀 — 소견을 만드는 사이 재분석됨 visitId={}", visitId);
            }
            return new KioskCommentResponse(aiComment);
        } finally {
            commentingVisits.remove(visitId);
        }
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
                entity.getAnalysisType(),
                entity.getConfidenceLevel(),
                lowConfidenceCaution.messageFor(entity.getConfidenceLevel(), top1Code(entity), fullImageCode(entity)),
                doctorHeatmapApiUrl(entity),
                entity.getAiComment(),
                entity.getAnalyzedAt(),
                entity.getHeatmapStyle(),
                HeatmapStorage.cropResults(entity.getHeatmapCrops(),
                        "/api/v1/visits/" + entity.getVisitId() + "/preliminary/heatmap/crops/"),
                fullImageTop1Result(entity)
        );
    }

    /** 태블릿용 — 토큰이 가리키는 접수의 히트맵만 반환한다. 인증 없이 열리는 경로다. */
    public ResponseEntity<byte[]> getHeatmapContentByToken(String token) {
        return heatmapResponse(resolveToken(token).getId());
    }

    /** 의사 진료 페이지용 — JWT 인증 경로(/api/v1/visits/{visitId}/preliminary/heatmap)에서 호출한다. */
    public ResponseEntity<byte[]> getHeatmapContentByVisitId(Long visitId) {
        return heatmapResponse(visitId);
    }

    /** 태블릿용 확대 히트맵 — 토큰이 가리키는 접수의 것만 준다. 인증 없이 열리는 경로다. */
    public ResponseEntity<byte[]> getHeatmapCropContentByToken(String token, int rank) {
        return heatmapCropResponse(resolveToken(token).getId(), rank);
    }

    /** 의사 진료 페이지용 확대 히트맵 — JWT 인증 경로(/api/v1/visits/{visitId}/preliminary/heatmap/crops/{rank}). */
    public ResponseEntity<byte[]> getHeatmapCropContentByVisitId(Long visitId, int rank) {
        return heatmapCropResponse(visitId, rank);
    }

    /**
     * 트랜잭션을 열지 않는다 — 아래 {@code download()} 는 S3 왕복이라 트랜잭션 안에 두면
     * 이미지 한 장 내려주는 동안 DB 커넥션을 붙잡게 된다. 조회는 쿼리 한 번이라 트랜잭션이 필요 없다.
     * ({@code AnalysisService.getHeatmapContent()} 도 같은 이유로 트랜잭션이 없다.)
     */
    private ResponseEntity<byte[]> heatmapResponse(Long visitId) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .filter(p -> p.getGradcamUrl() != null)
                .orElseThrow(() -> new NoSuchElementException("예비분석 히트맵이 없습니다. visitId=" + visitId));
        return jpegResponse(imageStorageService.download(entity.getGradcamUrl()));
    }

    /** heatmapResponse 와 같은 이유로 트랜잭션을 열지 않는다. */
    private ResponseEntity<byte[]> heatmapCropResponse(Long visitId, int rank) {
        HeatmapCrop crop = preliminaryAnalysisRepository.findByVisitId(visitId)
                .map(p -> HeatmapStorage.findCrop(p.getHeatmapCrops(), rank))
                .orElseThrow(() -> new NoSuchElementException(
                        "예비분석 확대 히트맵이 없습니다. visitId=" + visitId + " #" + rank));
        return jpegResponse(imageStorageService.download(crop.key()));
    }

    private static ResponseEntity<byte[]> jpegResponse(byte[] bytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                // 환자 병변 이미지다. 공유 캐시(nginx·프록시)에 남지 않도록 private 로 제한한다.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(bytes);
    }

    /**
     * QR 토큰 → Visit. 유효하지 않으면 404 — 태블릿에는 "유효하지 않은 QR"로 표시된다.
     * 종료(COMPLETED/CANCELLED)됐거나 당일이 지난 접수는 토큰이 맞아도 여기서 걸린다
     * (VisitRepository.findActiveByKioskToken 참고).
     */
    private Visit resolveToken(String token) {
        if (token == null || token.isBlank()) {
            throw new NoSuchElementException("유효하지 않은 키오스크 토큰입니다.");
        }
        return visitRepository.findActiveByKioskToken(token, LocalDate.now().atStartOfDay())
                .orElseThrow(() -> new NoSuchElementException("유효하지 않은 키오스크 토큰입니다."));
    }

    private PreliminaryAnalysisResponse toResponse(String token, PreliminaryAnalysis entity, FastApiPredictResponse prediction) {
        List<PreliminaryAnalysisResponse.TopKResult> topK = prediction.top5().stream()
                .map(r -> new PreliminaryAnalysisResponse.TopKResult(r.diseaseCode(), r.diseaseNameKo(), r.confidence()))
                .toList();

        return new PreliminaryAnalysisResponse(
                topK,
                entity.getAnalysisType(),
                entity.getConfidenceLevel(),
                lowConfidenceCaution.messageFor(entity.getConfidenceLevel(), prediction.top1().diseaseCode(),
                        fullImageCode(entity)),
                kioskHeatmapApiUrl(token, entity),
                entity.getAiComment(),
                entity.getAnalyzedAt(),
                entity.getHeatmapStyle(),
                // 태블릿에는 JWT 가 없다 — 확대 히트맵도 전체 히트맵처럼 토큰 경로로 내려준다.
                HeatmapStorage.cropResults(entity.getHeatmapCrops(), "/api/kiosk/session/" + token + "/heatmap/crops/"),
                fullImageTop1Result(entity)
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
    private FastApiPredictResponse callFastApi(byte[] imageBytes, AnalysisType task) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            // task 는 방문자가 고른 촬영 대상이다(V13). 기본 태스크여도 항상 명시해서 보낸다 — 기본값을 두 서버에
            // 따로 두면 언젠가 한쪽만 바뀐다(AnalysisService 와 같은 규칙).
            // crops 를 보내지 않는다 → 확대 보기·확대 재판정이 켜진다. 확대 히트맵과 재판정 전 원본 1순위는
            // preliminary_analysis 의 heatmap_crops·full_image_top1 에 남는다(V12) — 그 칸이 생기기 전에는 false 로 껐다.
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "image_base64", base64Image,
                    "task", task.code()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fastapiUrl + "/predict-base64"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", fastapiInternalSecret)
                    // 태블릿은 결과를 기다리는 화면이다. 무한 대기면 환자가 로딩만 보다 끝난다.
                    .timeout(Duration.ofSeconds(fastapiTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 503) {
                // 가중치가 없어 로드되지 않은 태스크가 이렇게 온다 — 우리 버그가 아니라 서버 준비 상태의 문제다
                // (AnalysisService.callFastApi 와 같은 처리).
                log.error("FastAPI 503(예비분석) task={} body={}", task.code(), response.body());
                throw new AiServiceUnavailableException(
                        "AI 분석 서버에 '" + task.code() + "' 모델이 준비되어 있지 않습니다. 접수처에 문의해 주세요.");
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("FastAPI 오류 " + response.statusCode() + ": " + response.body());
            }

            FastApiPredictResponse prediction = objectMapper.readValue(response.body(), FastApiPredictResponse.class);
            // 요청한 모델이 실제로 돌았는지 — 규칙은 FastApiPredictResponse.servedAs 주석.
            if (!prediction.servedAs(task)) {
                log.error("FastAPI 가 요청한 태스크로 추론하지 않았다(예비분석) requested={} served={}",
                        task.code(), prediction.task());
                throw new AiServiceUnavailableException(
                        "AI 분석 서버가 요청한 분석 종류(" + task.code() + ")를 지원하지 않습니다. 접수처에 문의해 주세요.");
            }
            return prediction;
        } catch (AiServiceUnavailableException e) {
            throw e;
        } catch (HttpConnectTimeoutException | ConnectException e) {
            log.error("FastAPI 연결 실패(예비분석) url={}", fastapiUrl, e);
            throw new AiServiceUnavailableException("AI 분석 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
        } catch (HttpTimeoutException e) {
            log.error("FastAPI 응답 시간 초과(예비분석) url={} timeout={}s", fastapiUrl, fastapiTimeoutSeconds);
            throw new AiServiceUnavailableException(
                    "AI 분석 서버가 " + fastapiTimeoutSeconds + "초 안에 응답하지 않았습니다. 잠시 후 다시 시도해주세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceUnavailableException("AI 분석 요청이 중단되었습니다.");
        } catch (Exception e) {
            throw new RuntimeException("FastAPI 예비분석 요청 실패: " + e.getMessage(), e);
        }
    }

    private static AnalysisResponse.FullImageTop1 fullImageTop1Result(PreliminaryAnalysis entity) {
        TopKItem item = entity.getFullImageTop1();
        if (item == null) return null;
        return new AnalysisResponse.FullImageTop1(
                item.code(), DISEASE_NAME_KO.getOrDefault(item.code(), item.code()), item.confidence());
    }

    /** 재판정 전 원본 사진의 1순위 코드. 원본이 악성·전암이면 재판정 뒤에도 위험 문구를 쓴다. 재판정하지 않았으면 null. */
    private static String fullImageCode(PreliminaryAnalysis entity) {
        return entity.getFullImageTop1() == null ? null : entity.getFullImageTop1().code();
    }

    /**
     * 저장된 예비분석에서 Top-1 병명 코드를 꺼낸다 — 경고 문구의 강도를 여기서 가른다.
     *
     * top_k_json 은 신뢰도 내림차순이라 0번이 Top-1 이다. 비어 있으면 null 을 주는데,
     * 그때 {@link LowConfidenceCaution} 은 "모르면 위험" 쪽으로 판단한다.
     */
    private static String top1Code(PreliminaryAnalysis entity) {
        List<TopKItem> topK = entity.getTopKJson();
        return (topK == null || topK.isEmpty()) ? null : topK.get(0).code();
    }
}
