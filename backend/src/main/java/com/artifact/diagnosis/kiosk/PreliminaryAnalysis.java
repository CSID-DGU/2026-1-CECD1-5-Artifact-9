package com.artifact.diagnosis.kiosk;

import com.artifact.diagnosis.analysis.LowConfidenceCaution;
import com.artifact.diagnosis.analysis.TopKItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 대기실 키오스크 예비분석 결과. Visit과 1:1.
 * Visit 상태(FSM)와는 분리된 사이드 채널 — 이 값이 채워져도 visit.status는 바뀌지 않는다.
 * DB 테이블: preliminary_analysis
 */
@Entity
@Table(name = "preliminary_analysis")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreliminaryAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preliminary_analysis_id")
    private Long id;

    @Column(name = "visit_id", nullable = false, unique = true)
    private Long visitId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_k_json", columnDefinition = "json")
    private List<TopKItem> topKJson;

    /**
     * AI 신뢰도 등급 — analysis_result 와 같은 값 체계다. AnalysisResult.confidenceLevel 주석 참고.
     *
     * 기본값이 DB 컬럼의 DEFAULT('normal')와 같아야 한다. Hibernate 는 값이 없으면 NULL 을
     * 명시적으로 넣기 때문에 DB DEFAULT 가 발동하지 않고 NOT NULL 위반으로 터진다.
     * ({@code @Builder.Default} 가 없으면 Lombok 이 이 초기값을 무시한다 — 빼지 말 것.)
     */
    @Builder.Default
    @Column(name = "confidence_level", nullable = false, length = 10)
    private String confidenceLevel = LowConfidenceCaution.LEVEL_NORMAL;

    @Column(name = "gradcam_url", length = 500)
    private String gradcamUrl;

    @Column(name = "ai_comment", columnDefinition = "TEXT")
    private String aiComment;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /**
     * 이 예측을 낸 FastAPI 모델 버전. AnalysisResult.modelVersion 과 같은 값 체계다.
     *
     * confidenceLevel 과 같은 이유로 {@code @Builder.Default} 가 필요하다 — Hibernate 는 값이
     * 없으면 NULL 을 명시적으로 넣기 때문에 DB DEFAULT('unknown')가 발동하지 않는다.
     */
    @Builder.Default
    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion = "unknown";

    /** 재분석 시 갱신되어야 하므로 CreationTimestamp 대신 서비스에서 직접 설정한다. */
    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
}
