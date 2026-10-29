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

    /**
     * 성분명이 이름에 든 외용제(연고·크림·겔·로션·외용액·폼). 처방 코멘트 프롬프트의 참고 약품 후보용이다
     * ({@code prescription.DrugCandidates}). 성분명은 코드에 박힌 고정 문자열이라 사용자 입력이 닿지 않는다.
     * PK 순으로 정렬해 같은 상병이면 늘 같은 후보가 나오게 한다.
     */
    @Query("""
            select d from DrugMaster d
            where d.nameKr like concat('%', :ingredient, '%')
              and (d.nameKr like '%연고%' or d.nameKr like '%크림%' or d.nameKr like '%겔%'
                   or d.nameKr like '%로션%' or d.nameKr like '%외용액%' or d.nameKr like '%폼%')
            order by d.id
            """)
    List<DrugMaster> findTopicalByIngredient(@Param("ingredient") String ingredient, Pageable pageable);
}
