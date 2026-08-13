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
 * <p>키오스크 예비분석 한 번에는 FastAPI 추론 + 히트맵 업로드 + Gemini 호출까지
 * 외부 왕복이 세 번 들어간다. 이걸 한 트랜잭션으로 감싸면 그 시간(수 초~수십 초) 내내
 * DB 커넥션이 묶인다. 태블릿 여러 대가 동시에 촬영하면 커넥션 풀이 그대로 말라버린다.
 *
 * <p>같은 빈 안에서 부르면 Spring 프록시를 타지 않아 {@code @Transactional} 이 무시되므로
 * 클래스를 분리했다. <b>여기에는 DB 작업만 둔다.</b>
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
     * <p>재촬영하면 같은 visit 의 기존 행을 덮어쓴다 — Visit 1건당 예비분석은 1건이다.
     */
    @Transactional
    public PreliminaryAnalysis saveResult(Long visitId, List<TopKItem> topK,
                                          String gradcamKey, String aiComment) {
        PreliminaryAnalysis entity = preliminaryAnalysisRepository.findByVisitId(visitId)
                .orElseGet(() -> PreliminaryAnalysis.builder().visitId(visitId).source(SOURCE).build());
        entity.setTopKJson(topK);
        entity.setGradcamUrl(gradcamKey);
        entity.setAiComment(aiComment);
        entity.setAnalyzedAt(LocalDateTime.now());
        return preliminaryAnalysisRepository.save(entity);
    }
}
