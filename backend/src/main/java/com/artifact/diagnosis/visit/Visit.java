package com.artifact.diagnosis.visit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;


/**
 * 내원/접수 1건. 환자 1명이 여러 Visit을 가질 수 있다.
 * 진료 흐름 전체가 이 엔티티의 status 전이로 표현된다.
 * DB 테이블: visit
 */

@Entity
@Table(name = "visit")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Visit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "visit_id")
    private Long id;

    /** patient.patient_id (FK). 단순화를 위해 ID만 보유. */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "visit_date", nullable = false)
    private LocalDateTime visitDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VisitStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ----- 도메인 행위 -----

    /** 의사가 진료를 시작할 때 호출 — 이미지 업로드 및 AI 분석 가능 상태로 전이 */
    public void startConsultation() {
        if (this.status != VisitStatus.RECEIVED) {
            throw new IllegalStateException(
                "접수 완료 상태에서만 진료를 시작할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.IN_PROGRESS;
    }

    /** AI 분석 요청 직전에 호출 */
    public void markAnalyzing() {
        if (this.status != VisitStatus.IN_PROGRESS && this.status != VisitStatus.ANALYZED) {
            throw new IllegalStateException(
                "AI 분석을 시작할 수 없는 상태입니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.ANALYZING;
    }

    /** AI 분석 응답을 받은 직후 호출 */
    public void markAnalyzed() {
        if (this.status != VisitStatus.ANALYZING) {
            throw new IllegalStateException(
                "분석 중이 아닙니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.ANALYZED;
    }

    /** AI 분석이 유효하지 않은 이미지로 중단된 경우 다시 진료중 상태로 복구 */
    public void rollbackAnalysis() {
        if (this.status != VisitStatus.ANALYZING) {
            throw new IllegalStateException(
                "분석 중 상태에서만 분석을 취소할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.IN_PROGRESS;
    }

    /** 의사가 병명을 확정한 시점 — AI 분석 없이 IN_PROGRESS에서 바로 확정도 가능 */
    public void markDiagnosed() {
        if (this.status != VisitStatus.ANALYZED && this.status != VisitStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                "진료 중 또는 AI 분석 완료 상태에서만 진단 확정이 가능합니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.DIAGNOSED;
    }

    /** 처방 저장 직후 호출 */
    public void markPrescribed() {
        if (this.status != VisitStatus.DIAGNOSED) {
            throw new IllegalStateException(
                "진단 확정 상태에서만 처방 저장이 가능합니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.PRESCRIBED;
    }

    /** 처방전 발급(=화면 표시) 후 진료 종료 */
    public void markCompleted() {
        if (this.status != VisitStatus.PRESCRIBED) {
            throw new IllegalStateException(
                "처방 저장 상태에서만 완료 처리가 가능합니다. 현재 상태: " + this.status);
        }
        this.status = VisitStatus.COMPLETED;
    }

    public void cancel() {
        this.status = VisitStatus.CANCELLED;
    }
}
