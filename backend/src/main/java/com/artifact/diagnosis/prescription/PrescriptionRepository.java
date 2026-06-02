package com.artifact.diagnosis.prescription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    /** 한 Visit 당 처방 1건이 일반적이라 단건 조회. */
    Optional<Prescription> findByVisitId(Long visitId);

    @Query("""
            select new com.artifact.diagnosis.prescription.PrescriptionPatientSummaryResponse(
                p.id, v.id, patient.id, patient.name, patient.birthDate, patient.gender,
                v.visitDate, v.status, p.memberId, p.memberName, p.prescribedAt
            )
            from Prescription p
            join Visit v on v.id = p.visitId
            join Patient patient on patient.id = v.patientId
            where p.memberId = :memberId
              and v.visitDate >= :start
              and v.visitDate < :end
            order by v.visitDate desc
            """)
    List<PrescriptionPatientSummaryResponse> findPatientSummariesByMemberIdAndVisitDateRange(
            Long memberId, LocalDateTime start, LocalDateTime end);
}
