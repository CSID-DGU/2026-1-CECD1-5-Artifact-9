package com.artifact.diagnosis.visit;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 키오스크 QR 토큰으로, 아직 유효한 접수만 조회한다.
     *
     * kioskToken 은 발급 후 영구히 남는 값이다 — 이 조건이 없으면 진료가 끝나거나 며칠이
     * 지난 접수도 같은 URL로 사진 업로드·예비분석이 계속 열려 있게 된다. 대기실 태블릿은
     * 그날 온 환자가 그 자리에서 쓰는 물건이므로, 종료되지 않은 상태 + 당일 접수로 좁힌다.
     */
    @Query("""
            select v from Visit v
            where v.kioskToken = :token
              and v.status not in (com.artifact.diagnosis.visit.VisitStatus.COMPLETED,
                                   com.artifact.diagnosis.visit.VisitStatus.CANCELLED)
              and v.visitDate >= :validFrom
            """)
    java.util.Optional<Visit> findActiveByKioskToken(@Param("token") String token,
                                                      @Param("validFrom") LocalDateTime validFrom);

    /**
     * 감열지 진료요약서 QR 의 열람 토큰으로 찾는다. kioskToken 과 별개의 값이다
     * (Visit.summaryToken 주석 참고 — 열람 전용이라 사진 업로드 권한이 딸려가지 않는다).
     */
    java.util.Optional<Visit> findBySummaryToken(String summaryToken);

    /**
     * 키오스크 자동 진입(QR 없이)이 잡을 대상 — 예비분석을 아직 하지 않은 접수 중 가장 최근 1건.
     *
     * 왜 최신순인가. 대기실 태블릿은 3초마다 이 API를 폴링하다가 대상이 생기면 곧바로 이동한다
     * (`KioskWaiting.tsx`). 즉 실제로 태블릿 앞에 서 있는 사람은 방금 접수한 환자다.
     * 오래된 순으로 고르면, 접수만 하고 태블릿을 쓰지 않은 접수 건이 하나라도 남아 있을 때
     * 태블릿이 그 접수를 영원히 반복해서 잡아 새 환자가 아무도 진입할 수 없게 된다.
     *
     * 조회 1번으로 끝낸다. 이전 구현은 전체 목록을 받아 행마다 예비분석 존재 여부를 따로 물어봤다(N+1).
     */
    @Query("""
            select v from Visit v
            where v.status = :status
              and not exists (
                  select 1 from PreliminaryAnalysis p where p.visitId = v.id
              )
            order by v.visitDate desc
            """)
    List<Visit> findLatestWithoutPreliminaryAnalysis(@Param("status") VisitStatus status, Limit limit);
}
