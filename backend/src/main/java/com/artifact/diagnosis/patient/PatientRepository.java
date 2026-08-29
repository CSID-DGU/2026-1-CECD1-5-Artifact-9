package com.artifact.diagnosis.patient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long>,
        JpaSpecificationExecutor<Patient> {

    /**
     * 이름으로 부분 검색 (조회 화면용).
     *
     * 파생 쿼리({@code findByNameContaining})를 쓰지 않는 이유는 거기엔 escape 절을 붙일 수 없어서다.
     * 그대로 두면 검색창에 {@code %} 한 글자만 넣어도 전체 환자가 나온다.
     * 패턴은 반드시 {@link com.artifact.diagnosis.common.util.LikeEscape#contains(String)} 로 만들 것.
     */
    @Query("select p from Patient p where p.name like :pattern escape '!'")
    List<Patient> searchByNamePattern(@Param("pattern") String pattern);

    /** 이름 + 전화번호로 기존 환자 검색 (중복 확인용). */
    Optional<Patient> findFirstByNameAndPhone(String name, String phone);
}
