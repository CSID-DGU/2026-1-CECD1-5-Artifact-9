package com.artifact.diagnosis.prescription;

import jakarta.persistence.*;
import lombok.*;

/**
 * 질병별 권장 처방 템플릿.
 * 의사가 병명을 선택하면 이 테이블의 해당 disease_id 행들을
 * 처방 폼에 prefill 한다.
 * DB 테이블: prescription_template
 */
@Entity
@Table(name = "prescription_template")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Long id;

    @Column(name = "disease_id", nullable = false)
    private Long diseaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prescription_type", nullable = false, length = 20)
    private PrescriptionType prescriptionType;

    @Column(name = "medicine_name", nullable = false, length = 200)
    private String medicineName;

    @Column(name = "dosage", length = 100)
    private String dosage;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
