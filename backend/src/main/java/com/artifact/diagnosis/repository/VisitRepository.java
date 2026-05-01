package com.artifact.diagnosis.repository;

import com.artifact.diagnosis.domain.Visit;
import com.artifact.diagnosis.domain.VisitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisitRepository extends JpaRepository<Visit, Long> {

    /** 환자별 진료 이력. 최신 접수가 위에 오도록. */
    List<Visit> findByPatientIdOrderByVisitDateDesc(Long patientId);

    /** 대시보드 — 특정 상태의 visit 목록 (예: 접수 대기열). */
    List<Visit> findByStatusOrderByVisitDateAsc(VisitStatus status);
}
