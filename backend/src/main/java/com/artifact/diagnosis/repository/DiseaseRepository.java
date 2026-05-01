package com.artifact.diagnosis.repository;

import com.artifact.diagnosis.domain.Disease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiseaseRepository extends JpaRepository<Disease, Long> {

    /**
     * AI 모델이 반환한 클래스 코드(예: "mel")로 disease 행을 찾는다.
     * AnalysisService 가 ML 응답을 DB ID 로 변환할 때 사용.
     */
    Optional<Disease> findByDiseaseCode(String diseaseCode);
}
