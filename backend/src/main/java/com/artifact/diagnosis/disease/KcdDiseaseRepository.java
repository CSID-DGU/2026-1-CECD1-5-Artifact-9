package com.artifact.diagnosis.disease;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KcdDiseaseRepository extends JpaRepository<KcdDisease, Long> {

    /** 코드 또는 한글 상병명으로 부분 검색 */
    Page<KcdDisease> findByCodeContainingOrNameKrContaining(
            String code, String nameKr, Pageable pageable);
}
