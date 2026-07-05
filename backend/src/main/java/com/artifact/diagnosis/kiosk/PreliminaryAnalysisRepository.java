package com.artifact.diagnosis.kiosk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PreliminaryAnalysisRepository extends JpaRepository<PreliminaryAnalysis, Long> {

    Optional<PreliminaryAnalysis> findByVisitId(Long visitId);

    boolean existsByVisitId(Long visitId);
}
