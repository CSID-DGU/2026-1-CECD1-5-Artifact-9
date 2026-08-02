package com.artifact.diagnosis.visit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface VisitRepository extends JpaRepository<Visit, Long> {

    /** 환자별 진료 이력. 최신 접수가 위에 오도록. */
    List<Visit> findByPatientIdOrderByVisitDateDesc(Long patientId);

    /** 대시보드 — 특정 상태의 visit 목록 (예: 접수 대기열). */
    List<Visit> findByStatusOrderByVisitDateAsc(VisitStatus status);

    /** 특정 날짜의 내원 목록. */
    List<Visit> findByVisitDateBetweenOrderByVisitDateAsc(LocalDateTime start, LocalDateTime end);

    /** 환자의 가장 최근 Visit (최종진료일 산출용). */
    java.util.Optional<Visit> findTopByPatientIdOrderByVisitDateDesc(Long patientId);

    /** 키오스크 QR 토큰으로 접수 조회. */
    java.util.Optional<Visit> findByKioskToken(String kioskToken);
}
