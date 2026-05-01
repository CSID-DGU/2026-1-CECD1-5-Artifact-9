package com.artifact.diagnosis.repository;

import com.artifact.diagnosis.domain.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    /** 한 Visit 당 처방 1건이 일반적이라 단건 조회. */
    Optional<Prescription> findByVisitId(Long visitId);
}
