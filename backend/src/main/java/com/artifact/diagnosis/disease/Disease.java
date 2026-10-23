package com.artifact.diagnosis.disease;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 질병 마스터 — AI 모델이 출력하는 클래스 코드의 합집합. 태스크별로 클래스 목록이 다르다.
 *   skin_lesion: HAM10000 7종 + SCIN 염증성 1종 (V1 시드, V7 보강)
 *   facial:      안면부 피부질환 6종 (V10 시드)
 * 시드는 Flyway 마이그레이션이 적재하고, 모델 클래스와 어긋나지 않는지
 * fastapi/tests/test_model_contract.py [6] 이 모든 마이그레이션의 disease INSERT 를 합쳐 대조한다.
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

    /**
     * 모델 클래스 코드 — {@code AnalysisResult.topKResults} 의 code 와 같은 값.
     *   skin_lesion: nv, mel, bkl, bcc, akiec, df, vasc, inflammatory
     *   facial:      acne, atopic, normal, psoriasis, rosacea, seb_derm
     */
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
