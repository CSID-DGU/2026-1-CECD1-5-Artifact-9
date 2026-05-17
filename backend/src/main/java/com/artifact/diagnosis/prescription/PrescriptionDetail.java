package com.artifact.diagnosis.prescription;

import jakarta.persistence.*;
import lombok.*;

/**
 * 처방 상세 1줄. 한 처방(Prescription)에 여러 줄이 매달린다.
 *
 * Prescription 엔티티의 단방향 @OneToMany 로 관리되며,
 * prescription_id 컬럼은 부모쪽 @JoinColumn 으로 자동 채워진다.
 *
 * DB 테이블: prescription_detail
 */
@Entity
@Table(name = "prescription_detail")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detail_id")
    private Long id;

    /** drug_master.drug_id — 마스터에서 선택 시 설정, 직접 입력 시 null */
    @Column(name = "drug_id")
    private Long drugId;

    /** 약품명 — drug_master에서 복사하거나 직접 입력 */
    @Column(name = "medicine_name", nullable = false, length = 300)
    private String medicineName;

    @Column(name = "dosage", length = 100)
    private String dosage;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
