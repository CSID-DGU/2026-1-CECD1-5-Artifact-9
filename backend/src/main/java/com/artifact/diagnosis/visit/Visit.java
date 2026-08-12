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

    @Column(name = "reception_memo", columnDefinition = "TEXT")
    private String receptionMemo;

    /**
     * 대기실 키오스크 QR 진입용 토큰. 접수 시 발급되며 URL(/kiosk/{token})에 그대로 실린다.
     * visit_id는 순차 정수라 LAN에서 추측 가능하므로, 태블릿에 노출되는 경로는 이 불투명 값으로 받는다.
     */
    @Column(name = "kiosk_token", length = 12, unique = true)
    private String kioskToken;

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

    /**
     * 처방 저장 시점의 상태 전이.
     *
     * 진료 화면에는 별도의 "진단 확정" 단계가 없고 처방 저장 버튼 하나뿐이다 —
     * 즉 처방을 저장하는 행위 자체가 진단 확정이다. 그래서 아직 확정 전이면 여기서 함께 전이시킨다.
     *   - IN_PROGRESS: AI 분석 없이 의사가 육안으로 진단하고 바로 처방하는 경로 (이미지 업로드 불필요)
     *   - ANALYZED:    AI 분석 결과를 검토한 뒤 처방하는 경로
     *   - PRESCRIBED:  재처방 — 상태는 그대로 두고 처방 내용만 갱신한다
     *
     * 전이를 클라이언트가 두 번의 API 호출로 조립하면 "진단 확정만 성공하고 처방은 실패"한
     * 중간 상태가 남을 수 있어, 한 트랜잭션 안에서 처리한다.
     */
    public void confirmDiagnosisAndPrescribe() {
        switch (this.status) {
            case IN_PROGRESS, ANALYZED -> {
                markDiagnosed();
                markPrescribed();
            }
            case DIAGNOSED -> markPrescribed();
            case PRESCRIBED -> { /* 재처방 — 상태 유지 */ }
            default -> throw new IllegalStateException(
                "처방을 저장할 수 없는 상태입니다. 현재 상태: " + this.status);
        }
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
