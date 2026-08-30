package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.disease.Disease;
import com.artifact.diagnosis.disease.DiseaseRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitImage;
import com.artifact.diagnosis.visit.VisitImageRepository;
import com.artifact.diagnosis.visit.VisitRepository;
import com.artifact.diagnosis.visit.VisitStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * {@link AnalysisService} 의 DB 작업만 떼어낸 트랜잭션 경계.
 *
 * 왜 클래스를 나눴는가. 취향이 아니라 Spring 프록시 동작 때문이다.
 * 같은 빈 안에서 {@code this.saveResult()} 를 부르면 프록시를 거치지 않아
 * {@code @Transactional} 이 조용히 무시된다. 외부 호출(FastAPI 추론·스토리지 업로드)을
 * 트랜잭션 밖으로 빼려면 DB 구간이 다른 빈에 있어야 한다.
 *
 * 규칙: 이 클래스의 메서드는 DB만 만진다. HTTP 호출이나 S3 업로드를 여기에 넣는 순간
 * 그 시간만큼 DB 커넥션을 붙잡게 되고, 원래 고치려던 문제로 그대로 되돌아간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AnalysisTransactionService {

    private final VisitRepository visitRepository;
    private final VisitImageRepository visitImageRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final AnalysisImageRepository analysisImageRepository;
    private final DiseaseRepository diseaseRepository;

    /**
     * 분석 시작 표시 + 추론에 넘길 이미지 경로. 실패 시 되돌릴 수 있도록 직전 상태도 함께 돌려준다.
     *
     * 리스트인 이유. 지금 모델은 1장만 받지만, 다중 입력 모델로 바뀌어도
     * 이 record 와 아래 두 메서드는 그대로 쓸 수 있게 해 둔 것이다.
     * 확장 시 실제로 고쳐야 하는 곳은 {@code AnalysisService.selectInferenceTargets} 한 군데다.
     */
    record AnalysisTarget(List<String> imageUrls, VisitStatus previousStatus) {

        /** 단일 입력 모델용 접근자. 다중 입력이 되면 호출부가 {@link #imageUrls()} 를 그대로 쓴다. */
        String primaryImageUrl() {
            return imageUrls.get(0);
        }
    }

    /**
     * @param imageIds 모델에 실제로 넣을 이미지들. 화면에서 고른 것 전부가 아니라
     *                 {@code AnalysisService.selectInferenceTargets} 가 추려낸 결과다.
     */
    @Transactional
    public AnalysisTarget beginAnalysis(Long visitId, List<Long> imageIds) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));

        List<String> imageUrls = new ArrayList<>(imageIds.size());
        for (Long imageId : imageIds) {
            VisitImage image = visitImageRepository.findById(imageId)
                    .orElseThrow(() -> new NoSuchElementException("이미지를 찾을 수 없습니다: " + imageId));

            // 소유 검사. 예전에는 이 확인이 없어서 POST /visits/5/analysis {"imageIds":[999]} 로
            // **다른 환자의 사진**을 분석하고 그 결과를 5번 접수에 붙일 수 있었다.
            // 결과에 이미지 참조가 남지 않던 시절에는 흔적조차 안 남았지만, 이제 analysis_image 가
            // 두 축을 FK 로 묶으므로 그 거짓말이 DB에 영구히 박힌다. 여기서 막는다.
            if (!Objects.equals(image.getVisitId(), visitId)) {
                throw new IllegalArgumentException(
                        "해당 접수의 이미지가 아닙니다: visitId=" + visitId + ", imageId=" + imageId);
            }
            imageUrls.add(image.getImageUrl());
        }

        VisitStatus previous = visit.getStatus();
        visit.markAnalyzing();
        return new AnalysisTarget(List.copyOf(imageUrls), previous);
    }

    /**
     * 추론이 실패했을 때 ANALYZING 을 직전 상태로 되돌린다.
     *
     * 이걸 빠뜨리면 접수가 ANALYZING 에 갇힌다 — {@code markAnalyzing()} 이 그 상태를 거부하므로
     * 재시도조차 막히고, 의사는 DB를 직접 고치기 전까지 그 환자를 진행시킬 수 없다.
     */
    @Transactional
    public void abortAnalysis(Long visitId, VisitStatus previous) {
        visitRepository.findById(visitId).ifPresent(visit -> {
            if (visit.getStatus() != VisitStatus.ANALYZING) {
                // 그 사이 다른 요청이 상태를 바꿨다. 여기서 되돌리면 남의 결과를 덮어쓰게 되므로 손대지 않는다.
                log.warn("분석 실패 복구 생략 visitId={} 현재상태={}", visitId, visit.getStatus());
                return;
            }
            visit.rollbackAnalysis(previous);
        });
    }

    /**
     * 분석 결과 저장 + 근거 이미지 매핑 + ANALYZED 전이. 외부 호출이 모두 끝난 뒤에만 호출한다.
     *
     * 결과 행과 이미지 매핑은 반드시 같은 트랜잭션이어야 한다. 나눠 놓으면 결과만 저장되고
     * 매핑이 빠진 행이 생길 수 있는데, 그건 이 테이블이 3개월간 비어 있던 상태와 정확히 같다.
     *
     * @param imageIds 모델에 실제로 넣은 이미지들 — {@link #beginAnalysis} 에 넘긴 것과 같은 목록
     */
    @Transactional
    public AnalysisResult saveResult(Long visitId, List<Long> imageIds,
                                     String modelVersion, String diseaseCode,
                                     double confidence, String confidenceLevel,
                                     List<TopKItem> topK,
                                     int inferenceMs, String heatmapKey) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));
        Disease disease = diseaseRepository.findByDiseaseCode(diseaseCode)
                .orElseThrow(() -> new IllegalStateException("알 수 없는 병명 코드: " + diseaseCode));

        AnalysisResult result = analysisResultRepository.save(AnalysisResult.builder()
                .visitId(visitId)
                .modelVersion(modelVersion)
                .predictedDiseaseId(disease.getId())
                .confidence(BigDecimal.valueOf(confidence))
                .confidenceLevel(confidenceLevel)
                .topKResults(topK)
                .inferenceTimeMs(inferenceMs)
                .heatmapImageUrl(heatmapKey)
                .build());

        // 어느 사진에서 나온 결과인지 남긴다.
        // analysis_result 에는 이미지 컬럼이 없으므로 **이 매핑이 유일한 근거 기록**이다.
        // 여기가 빠지면 "이 진단은 무엇을 보고 내렸나"에 DB가 영영 답할 수 없게 된다.
        analysisImageRepository.saveAll(imageIds.stream()
                .map(imageId -> AnalysisImage.builder()
                        .analysisId(result.getId())
                        .imageId(imageId)
                        .build())
                .toList());

        visit.markAnalyzed();
        return result;
    }
}
