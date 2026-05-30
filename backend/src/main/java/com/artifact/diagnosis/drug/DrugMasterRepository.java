package com.artifact.diagnosis.drug;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DrugMasterRepository extends JpaRepository<DrugMaster, Long> {

    /** 처방코드 또는 한글 처방명으로 부분 검색 */
    Page<DrugMaster> findByCodeContainingOrNameKrContaining(
            String code, String nameKr, Pageable pageable);

    List<DrugMaster> findByNameKrContaining(String keyword, Pageable pageable);
}
