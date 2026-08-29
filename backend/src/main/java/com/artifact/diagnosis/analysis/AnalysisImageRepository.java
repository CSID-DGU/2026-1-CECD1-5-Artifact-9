package com.artifact.diagnosis.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnalysisImageRepository
        extends JpaRepository<AnalysisImage, AnalysisImage.Key> {

    /**
     * 이 분석이 근거로 삼은 이미지 목록.
     *
     * 쓰기만 하고 아무도 읽지 않는 테이블은 결국 조용히 썩는다 — 이 테이블이 3개월간
     * 비어 있던 것을 아무도 몰랐던 이유가 정확히 그것이다. 그래서 저장 경로와 함께
     * 조회 경로도 열어 두고, {@code AnalysisResponse.analyzedImageIds} 로 화면까지 올린다.
     */
    List<AnalysisImage> findByAnalysisIdOrderByImageIdAsc(Long analysisId);
}
