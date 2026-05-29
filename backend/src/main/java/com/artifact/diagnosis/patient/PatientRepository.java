package com.artifact.diagnosis.patient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    /** 접수 시 전화번호로 기존 환자 검색. */
    Optional<Patient> findFirstByPhone(String phone);

    /** 이름으로 부분 검색 (관리자 화면용). */
    List<Patient> findByNameContaining(String name);

    /** 이름 + 전화번호로 기존 환자 검색 (중복 등록 방지). */
    Optional<Patient> findFirstByNameAndPhone(String name, String phone);
}
