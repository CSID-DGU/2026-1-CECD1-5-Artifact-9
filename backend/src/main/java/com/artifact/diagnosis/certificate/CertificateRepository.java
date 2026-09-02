package com.artifact.diagnosis.certificate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /** 환자별 발급이력 — 증명 탭 좌측 목록. 최근 발급이 위로 온다. */
    List<Certificate> findByPatientIdOrderByIssuedAtDesc(Long patientId);

    /** 특정 내원에서 발급된 서류들. */
    List<Certificate> findByVisitIdOrderByIssuedAtDesc(Long visitId);

    /**
     * 감열지 발급확인증 QR 의 열람 토큰으로 찾는다. 로그인 없이 열리는 경로가 쓰는
     * 유일한 조회라, 토큰을 모르면 어떤 증명서에도 닿지 않는다는 점이 곧 접근 통제다.
     */
    Optional<Certificate> findByShareToken(String shareToken);
}
