package com.artifact.diagnosis.prescription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PrescriptionTemplateRepository extends JpaRepository<PrescriptionTemplate, Long> {

    /** 의사가 병명을 선택했을 때 처방 폼에 prefill 할 권장 처방 목록. */
    List<PrescriptionTemplate> findByDiseaseId(Long diseaseId);
}
