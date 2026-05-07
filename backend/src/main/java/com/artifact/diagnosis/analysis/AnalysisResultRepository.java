package com.artifact.diagnosis.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    /** Visit 1건의 모든 분석 이력 (재분석 가능하므로 N건). 최신순. */
    List<AnalysisResult> findByVisitIdOrderByAnalyzedAtDesc(Long visitId);

    /** Visit의 가장 최근 분석 결과 1건. */
    Optional<AnalysisResult> findFirstByVisitIdOrderByAnalyzedAtDesc(Long visitId);
}
