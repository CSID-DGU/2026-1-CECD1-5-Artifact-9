package com.artifact.diagnosis.drug;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DrugMasterRepository extends JpaRepository<DrugMaster, Long> {

    /**
     * 처방코드 또는 한글 처방명으로 부분 검색.
     *
     * 패턴은 {@link com.artifact.diagnosis.common.util.LikeEscape#contains(String)} 로 만들어 넘긴다.
     * 파생 쿼리에는 escape 절을 붙일 수 없어 {@code @Query} 로 바꿨다 — 환자 검색과 같은 이유다.
     */
    @Query("""
            select d from DrugMaster d
            where d.code like :pattern escape '!'
               or d.nameKr like :pattern escape '!'
            """)
    Page<DrugMaster> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

    /** Gemini 프롬프트용 예시 약품 조회. 인자가 코드에 박힌 고정 문자열이라 사용자 입력이 닿지 않는다. */
    List<DrugMaster> findByNameKrContaining(String keyword, Pageable pageable);
}
