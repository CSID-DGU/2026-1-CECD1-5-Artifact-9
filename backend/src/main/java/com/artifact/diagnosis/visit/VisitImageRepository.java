package com.artifact.diagnosis.visit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisitImageRepository extends JpaRepository<VisitImage, Long> {

    /** 특정 접수의 이미지 전체 — 업로드 시각 오름차순 */
    List<VisitImage> findByVisitIdOrderByUploadedAtAsc(Long visitId);
}
