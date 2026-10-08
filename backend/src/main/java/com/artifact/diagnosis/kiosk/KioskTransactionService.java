package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.TopKItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link KioskService} 의 DB 쓰기 구간만 떼어낸 트랜잭션 경계.
 *
 * 키오스크 예비분석에는 FastAPI 추론 + 히트맵 업로드(그리고 별도 경로의 Gemini 호출)까지
 * 외부 왕복이 들어간다. 이걸 한 트랜잭션으로 감싸면 그 시간(수 초~수십 초) 내내
 * DB 커넥션이 묶인다. 태블릿 여러 대가 동시에 촬영하면 커넥션 풀이 그대로 말라버린다.
 *
 * 같은 빈 안에서 부르면 Spring 프록시를 타지 않아 {@code @Transactional} 이 무시되므로
 * 클래스를 분리했다. 여기에는 DB 작업만 둔다.
 */
@Service
@RequiredArgsConstructor
class KioskTransactionService {

    private final PreliminaryAnalysisRepository preliminaryAnalysisRepository;

    /** 예비분석 모델 소스 — 현재는 임상 사진용 단일 모델만 존재. FastAPI에 라우터가 생기면 여기서 전달한다. */
    private static final String SOURCE = "clinic";

    /**
     * 예비분석 결과 저장(upsert). 외부 호출이 모두 끝난 뒤에만 부른다.
     *
     * 재촬영하면 같은 visit 의 기존 행을 덮어쓴다 — Visit 1건당 예비분석은 1건이다.
     */
    @Transactional
    public PreliminaryAnalysis saveResult(Long visitId, List<TopKItem> topK, String confidenceLevel,
                                          String gradcamKey, String aiComment, String modelVersion) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .orElseGet(() -> PreliminaryAnalysis.builder().visitId(visitId).source(SOURCE).build());
        entity.setTopKJson(topK);
        entity.setConfidenceLevel(confidenceLevel);
        entity.setGradcamUrl(gradcamKey);
        entity.setAiComment(aiComment);
        entity.setModelVersion(modelVersion);
        entity.setAnalyzedAt(LocalDateTime.now());
        return preliminaryAnalysisRepository.save(entity);
    }

    /**
     * 참고 소견만 갱신한다. Gemini 호출을 분석 응답에서 떼어내면서 생긴 두 번째 쓰기다.
     *
     * 위 {@link #saveResult} 를 재사용하지 않는 이유가 있다. 그쪽은 topK·히트맵·모델버전까지
     * 전부 덮어쓰므로, 소견을 만드는 동안(최대 48초) 환자가 사진을 다시 찍었다면 뒤늦게 도착한
     * 소견 저장이 새 분석 결과를 통째로 옛 값으로 되돌린다.
     *
     * {@code expectedAnalyzedAt} 은 그 재촬영을 잡아내는 장치다. 분석 시각이 달라졌다면 지금
     * 들고 있는 소견은 **이전 사진**에 대한 것이므로 저장하지 않는다. 저장하면 환자가 방금 찍은
     * 사진 옆에서 직전 사진의 소견을 읽게 된다 — 의료 데모에서 가장 피해야 할 오표시다.
     * (analyzed_at 은 초 단위라 1초 안에 두 번 분석하면 이 검사를 통과한다. 촬영·추론에
     *  최소 수 초가 걸리므로 실제로는 일어나지 않는다.)
     *
     * @return 저장했으면 true, 그 사이 재분석이 일어나 건너뛰었으면 false
     */
    @Transactional
    public boolean saveAiComment(Long visitId, String aiComment, LocalDateTime expectedAnalyzedAt) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId).orElse(null);
        if (entity == null || !expectedAnalyzedAt.equals(entity.getAnalyzedAt())) {
            return false;
        }
        entity.setAiComment(aiComment);
        preliminaryAnalysisRepository.save(entity);
        return true;
    }
}
