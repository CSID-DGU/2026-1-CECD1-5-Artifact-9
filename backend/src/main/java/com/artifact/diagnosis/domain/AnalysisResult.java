package com.artifact.diagnosis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 분석 결과 1건.
 * Visit과 1:N — 같은 이미지를 여러 번 재분석할 수 있음(DB 코멘트).
 * DB 테이블: analysis_result
 */
@Entity
@Table(name = "analysis_result")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long id;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    /** Top-1 예측 disease_id (FK) */
    @Column(name = "predicted_disease_id", nullable = false)
    private Long predictedDiseaseId;

    /** 0.0000 ~ 1.0000 (DECIMAL(5,4)) */
    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    /** Top-3 후보 — Hibernate 6의 JSON 매핑으로 자동 직렬화 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_k_results", columnDefinition = "json")
    private List<TopKItem> topKResults;

    @Column(name = "inference_time_ms")
    private Integer inferenceTimeMs;

    @CreationTimestamp
    @Column(name = "analyzed_at", nullable = false, updatable = false)
    private LocalDateTime analyzedAt;
}
