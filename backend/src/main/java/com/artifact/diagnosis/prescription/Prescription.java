package com.artifact.diagnosis.prescription;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 최종 처방 헤더. 진료완료(처방) API가 만든다.
 *
 * details 컬렉션에 PrescriptionDetail을 담아 같이 save하면
 * Cascade로 한 트랜잭션 안에 처방 + 상세가 함께 저장된다.
 *
 * DB 테이블: prescription
 */
@Entity
@Table(name = "prescription")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prescription_id")
    private Long id;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    /** 의사가 선택한 KCD 상병코드 ID */
    @Column(name = "kcd_disease_id", nullable = false)
    private Long kcdDiseaseId;

    /** 어떤 AI 분석 결과를 근거로 했는지 (nullable) */
    @Column(name = "analysis_id")
    private Long analysisId;

    @CreationTimestamp
    @Column(name = "prescribed_at", nullable = false, updatable = false)
    private LocalDateTime prescribedAt;

    @Column(name = "revisit_recommended_date")
    private LocalDate revisitRecommendedDate;

    @Column(name = "doctor_notes", columnDefinition = "TEXT")
    private String doctorNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 처방 상세 줄들 — Prescription 저장 시 함께 INSERT 됨. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    @Builder.Default
    private List<PrescriptionDetail> details = new ArrayList<>();

    public void addDetail(PrescriptionDetail detail) {
        this.details.add(detail);
    }
}
