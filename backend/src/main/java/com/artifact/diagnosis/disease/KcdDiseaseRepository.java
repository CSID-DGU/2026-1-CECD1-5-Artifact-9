package com.artifact.diagnosis.disease;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KcdDiseaseRepository extends JpaRepository<KcdDisease, Long> {

    /**
     * 코드 또는 한글 상병명으로 부분 검색.
     *
     * <p>패턴은 {@link com.artifact.diagnosis.common.util.LikeEscape#contains(String)} 로 만들어 넘긴다.
     * 파생 쿼리에는 escape 절을 붙일 수 없어 {@code @Query} 로 바꿨다 — 환자 검색과 같은 이유다.
     */
    @Query("""
            select d from KcdDisease d
            where d.code like :pattern escape '!'
               or d.nameKr like :pattern escape '!'
            """)
    Page<KcdDisease> searchByPattern(@Param("pattern") String pattern, Pageable pageable);
}
