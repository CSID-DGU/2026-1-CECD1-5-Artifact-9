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

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "member_name", nullable = false, length = 50)
    private String memberName;

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

    /** LLM이 생성한 처방 코멘트 본문. doctor_notes와 별도 컬럼으로 구분. */
    @Column(name = "ai_comment", columnDefinition = "TEXT")
    private String aiComment;

    /** 코멘트를 생성한 LLM 모델 식별자 (예: gemini-3.1-flash-lite). */
    @Column(name = "ai_comment_model", length = 100)
    private String aiCommentModel;

    /** LLM 코멘트 생성 시각. 프론트엔드가 코멘트를 수신한 시점 기준. */
    @Column(name = "ai_comment_generated_at")
    private LocalDateTime aiCommentGeneratedAt;

    /** 의사가 저장 전 코멘트를 수정했으면 true. */
    @Column(name = "ai_comment_edited")
    private Boolean aiCommentEdited;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "prescription")
    @Builder.Default
    private List<PrescriptionDisease> diseases = new ArrayList<>();

    /** 처방 상세 줄들 — Prescription 저장 시 함께 INSERT 됨. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "prescription")
    @Builder.Default
    private List<PrescriptionDetail> details = new ArrayList<>();

    public void addDisease(PrescriptionDisease disease) {
        disease.setPrescription(this);
        this.diseases.add(disease);
    }

    public void addDetail(PrescriptionDetail detail) {
        detail.setPrescription(this);
        this.details.add(detail);
    }
}
