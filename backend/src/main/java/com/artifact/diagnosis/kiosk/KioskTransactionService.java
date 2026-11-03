package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.AnalysisType;
import com.artifact.diagnosis.analysis.StoredHeatmap;
import com.artifact.diagnosis.analysis.TopKItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * {@link KioskService} 의 DB 쓰기 구간만 떼어낸 트랜잭션 경계.
 *
 * 키오스크 예비분석 한 번에는 FastAPI 추론 + 히트맵 업로드가, 참고 소견에는 LLM 호출이 들어간다.
 * 이걸 한 트랜잭션으로 감싸면 그 시간(수 초~수십 초) 내내 DB 커넥션이 묶인다.
 * 태블릿 여러 대가 동시에 촬영하면 커넥션 풀이 그대로 말라버린다.
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
     * 참고 소견은 비운다. 소견은 화면이 결과를 그린 뒤 따로 만든다({@link KioskService#generateComment}) —
     * 재촬영인데 이전 사진의 소견이 남아 있으면 환자가 새 사진 옆에서 옛 사진의 소견을 읽는다.
     */
    @Transactional
    public PreliminaryAnalysis saveResult(Long visitId, AnalysisType task, List<TopKItem> topK, String confidenceLevel,
                                          StoredHeatmap heatmap, TopKItem fullImageTop1, String modelVersion) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .orElseGet(() -> PreliminaryAnalysis.builder().visitId(visitId).source(SOURCE).build());
        entity.setAnalysisType(task.code());
        entity.setTopKJson(topK);
        entity.setConfidenceLevel(confidenceLevel);
        entity.setGradcamUrl(heatmap.key());
        // 재촬영하면 확대 히트맵·재판정 기록도 새 분석의 것으로 통째로 바꾼다 — 비어 있으면 비운다.
        // 옛 확대 히트맵이 남으면 새 히트맵 위에 옛 사진의 상자가 그려진다.
        entity.setHeatmapCrops(heatmap.cropsOrNull());
        entity.setHeatmapStyle(heatmap.style());
        entity.setFullImageTop1(fullImageTop1);
        entity.setAiComment(null);
        entity.setModelVersion(modelVersion);
        entity.setAnalyzedAt(LocalDateTime.now());
        return preliminaryAnalysisRepository.save(entity);
    }

    /**
     * 참고 소견만 채운다 — 소견을 분석 응답에서 떼어내며 생긴 두 번째 쓰기다.
     *
     * {@link #saveResult} 를 다시 쓰지 않는 이유: 그쪽은 topK·히트맵까지 전부 덮어쓴다. 소견을 만드는 동안(LLM 왕복,
     * 최대 수십 초) 환자가 사진을 다시 찍었다면, 늦게 도착한 소견 저장이 새 분석을 옛 값으로 되돌리게 된다.
     *
     * 그 재촬영은 히트맵 키로 알아본다. 히트맵 키는 분석마다 새로 만든다(visitId + 밀리초) — 지금 행의 키가
     * 소견을 만들기 시작할 때의 키와 다르면 들고 있는 소견은 **이전 사진**의 것이므로 저장하지 않는다.
     * analyzed_at 은 초 단위라 같은 초 안의 재분석을 가르지 못해 함께 보지 않는다.
     *
     * @return 저장했으면 true, 그 사이 재분석이 있어 건너뛰었으면 false
     */
    @Transactional
    public boolean saveAiComment(Long visitId, String expectedHeatmapKey, String aiComment) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId).orElse(null);
        if (entity == null || !Objects.equals(entity.getGradcamUrl(), expectedHeatmapKey)) {
            return false;
        }
        entity.setAiComment(aiComment);
        return true;
    }
}
