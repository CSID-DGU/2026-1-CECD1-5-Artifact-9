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
import java.util.List;
import java.util.NoSuchElementException;

/**
 * {@link AnalysisService} 의 DB 작업만 떼어낸 트랜잭션 경계.
 *
 * <p><b>왜 클래스를 나눴는가.</b> 취향이 아니라 Spring 프록시 동작 때문이다.
 * 같은 빈 안에서 {@code this.saveResult()} 를 부르면 프록시를 거치지 않아
 * {@code @Transactional} 이 조용히 무시된다. 외부 호출(FastAPI 추론·스토리지 업로드)을
 * 트랜잭션 밖으로 빼려면 DB 구간이 <b>다른 빈</b>에 있어야 한다.
 *
 * <p><b>규칙: 이 클래스의 메서드는 DB만 만진다.</b> HTTP 호출이나 S3 업로드를 여기에 넣는 순간
 * 그 시간만큼 DB 커넥션을 붙잡게 되고, 원래 고치려던 문제로 그대로 되돌아간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AnalysisTransactionService {

    private final VisitRepository visitRepository;
    private final VisitImageRepository visitImageRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final DiseaseRepository diseaseRepository;

    /** 분석 시작 표시 + 추론에 넘길 이미지 경로. 실패 시 되돌릴 수 있도록 직전 상태도 함께 돌려준다. */
    record AnalysisTarget(String imageUrl, VisitStatus previousStatus) {}

    @Transactional
    public AnalysisTarget beginAnalysis(Long visitId, Long imageId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));
        VisitImage image = visitImageRepository.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("이미지를 찾을 수 없습니다: " + imageId));

        VisitStatus previous = visit.getStatus();
        visit.markAnalyzing();
        return new AnalysisTarget(image.getImageUrl(), previous);
    }

    /**
     * 추론이 실패했을 때 ANALYZING 을 직전 상태로 되돌린다.
     *
     * <p>이걸 빠뜨리면 접수가 ANALYZING 에 갇힌다 — {@code markAnalyzing()} 이 그 상태를 거부하므로
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

    /** 분석 결과 저장 + ANALYZED 전이. 외부 호출이 모두 끝난 뒤에만 호출한다. */
    @Transactional
    public AnalysisResult saveResult(Long visitId, String modelVersion, String diseaseCode,
                                     double confidence, List<TopKItem> topK,
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
                .topKResults(topK)
                .inferenceTimeMs(inferenceMs)
                .heatmapImageUrl(heatmapKey)
                .build());

        visit.markAnalyzed();
        return result;
    }
}
