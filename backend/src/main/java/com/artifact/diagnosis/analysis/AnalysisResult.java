package com.artifact.diagnosis.analysis;

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

    /**
     * 어느 태스크(모델)의 결과인가 — {@link AnalysisType#code()} 문자열. V10__facial_task.sql 에서 추가.
     *
     * enum 이 아니라 String 으로 두는 이유. {@code @Enumerated(STRING)} 은 코드가 아니라 enum 이름(SKIN_LESION)을
     * 저장해 FastAPI·프론트가 쓰는 소문자 코드와 또 하나의 사본이 생기고, 나중에 태스크를 내리더라도 저장된 기록은
     * 읽혀야 하는데 enum 컬럼은 상수를 지우는 순간 그 행들이 역직렬화에서 터진다.
     *
     * 기본값은 DB 컬럼의 DEFAULT('skin_lesion')와 맞춰 둔다. confidenceLevel 과 같은 이유다 — Hibernate 는
     * 값이 없으면 NULL 을 명시적으로 넣어서 DB DEFAULT 가 발동하지 않고 NOT NULL 위반이 난다.
     * ({@code @Builder.Default} 가 없으면 Lombok 이 이 초기값을 무시한다 — 빼지 말 것.)
     */
    @Builder.Default
    @Column(name = "analysis_type", nullable = false, length = 20)
    private String analysisType = AnalysisType.DEFAULT.code();

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    /** Top-1 예측 disease_id (FK) */
    @Column(name = "predicted_disease_id", nullable = false)
    private Long predictedDiseaseId;

    /** 0.0000 ~ 1.0000 (DECIMAL(5,4)) */
    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    /**
     * AI 신뢰도 등급 — "low" 면 화면에 확신도 경고가 붙는다. {@link LowConfidenceCaution} 참고.
     *
     * confidence 로 매번 다시 계산하지 않고 저장해 두는 이유: 경고선은 앞으로 조정될 수 있고,
     * 그러면 과거 진료 기록의 경고 여부까지 소급해서 바뀐다. 모델 버전을 상수로 두지 않고
     * 저장하는 것과 같은 이유다. 근거는 V6__confidence_level.sql 주석에.
     *
     * 기본값은 DB 컬럼의 DEFAULT('normal')와 맞춰 둔다. Hibernate 는 값이 없으면 NULL 을
     * 명시적으로 넣어서 DB DEFAULT 가 발동하지 않고 NOT NULL 위반이 난다.
     * ({@code @Builder.Default} 가 없으면 Lombok 이 이 초기값을 무시한다 — 빼지 말 것.)
     */
    @Builder.Default
    @Column(name = "confidence_level", nullable = false, length = 10)
    private String confidenceLevel = LowConfidenceCaution.LEVEL_NORMAL;

    /** Top-3 후보 — Hibernate 6의 JSON 매핑으로 자동 직렬화 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_k_results", columnDefinition = "json")
    private List<TopKItem> topKResults;

    @Column(name = "inference_time_ms")
    private Integer inferenceTimeMs;

    @CreationTimestamp
    @Column(name = "analyzed_at", nullable = false, updatable = false)
    private LocalDateTime analyzedAt;

    @Column(name = "heatmap_image_url", length = 500)
    private String heatmapImageUrl;

}
