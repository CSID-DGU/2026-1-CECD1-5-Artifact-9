package com.artifact.diagnosis.certificate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /** 환자별 발급이력 — 증명 탭 좌측 목록. 최근 발급이 위로 온다. */
    List<Certificate> findByPatientIdOrderByIssuedAtDesc(Long patientId);

    /** 특정 내원에서 발급된 서류들. */
    List<Certificate> findByVisitIdOrderByIssuedAtDesc(Long visitId);
}
