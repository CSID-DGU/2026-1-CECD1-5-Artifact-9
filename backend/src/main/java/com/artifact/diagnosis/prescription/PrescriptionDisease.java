package com.artifact.diagnosis.prescription;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "prescription_disease")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionDisease {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @Column(name = "kcd_disease_id", nullable = false)
    private Long kcdDiseaseId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;
}
