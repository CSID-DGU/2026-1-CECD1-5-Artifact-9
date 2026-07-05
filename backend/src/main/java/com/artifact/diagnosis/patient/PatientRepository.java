package com.artifact.diagnosis.patient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long>,
        JpaSpecificationExecutor<Patient> {

    /** 이름으로 부분 검색 (조회 화면용). */
    List<Patient> findByNameContaining(String name);

    /** 이름 + 전화번호로 기존 환자 검색 (중복 확인용). */
    Optional<Patient> findFirstByNameAndPhone(String name, String phone);
}
