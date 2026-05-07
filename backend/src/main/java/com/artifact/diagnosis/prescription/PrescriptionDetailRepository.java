package com.artifact.diagnosis.prescription;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PrescriptionDetail 은 보통 Prescription 엔티티의 details 컬렉션을 통해
 * cascade로 다뤄지지만, 단독 조회/삭제가 필요할 때를 위해 둠.
 */
public interface PrescriptionDetailRepository extends JpaRepository<PrescriptionDetail, Long> {
}
