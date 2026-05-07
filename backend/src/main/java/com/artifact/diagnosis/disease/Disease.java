package com.artifact.diagnosis.disease;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 질병 마스터 (HAM10000 7-class). 초기 데이터는 init SQL이 적재.
 * DB 테이블: disease
 */
@Entity
@Table(name = "disease")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Disease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "disease_id")
    private Long id;

    /** HAM10000 코드 — nv, mel, bkl, bcc, akiec, df, vasc */
    @Column(name = "disease_code", nullable = false, length = 20, unique = true)
    private String diseaseCode;

    @Column(name = "name_ko", nullable = false, length = 100)
    private String nameKo;

    @Column(name = "name_en", length = 100)
    private String nameEn;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private Severity severity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
