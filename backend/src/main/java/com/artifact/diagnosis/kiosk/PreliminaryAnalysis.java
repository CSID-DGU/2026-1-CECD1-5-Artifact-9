package com.artifact.diagnosis.kiosk;

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

    /** AI 신뢰도 등급 — analysis_result 와 같은 값 체계다. AnalysisResult.confidenceLevel 주석 참고. */
    @Column(name = "confidence_level", nullable = false, length = 10)
    private String confidenceLevel;

    @Column(name = "gradcam_url", length = 500)
    private String gradcamUrl;

    @Column(name = "ai_comment", columnDefinition = "TEXT")
    private String aiComment;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /** 재분석 시 갱신되어야 하므로 CreationTimestamp 대신 서비스에서 직접 설정한다. */
    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
}
