package com.artifact.diagnosis.certificate;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 제증명 발급 기록 한 건. 실제 병원의 "제증명 발급대장" 한 줄에 해당한다.
 *
 * 핵심은 {@link #content} 다. 발급 당시 종이에 찍힌 값 전체를 JSON으로 굳혀 보관한다.
 * 진단서를 떼어준 뒤 의사가 처방을 고치더라도(재처방은 기존 처방을 지우고 다시 만든다),
 * 이미 환자 손에 나간 서류와 시스템이 보여주는 내용이 달라지면 안 된다.
 * 그래서 재발급은 지금 데이터로 다시 만드는 것이 아니라 이 스냅샷을 그대로 다시 출력한다.
 *
 * DB 테이블: certificate (db/migration/V4__certificate.sql)
 */
@Entity
@Table(name = "certificate")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "certificate_id")
    private Long id;

    /**
     * 발급번호. INSERT로 PK를 받은 뒤 {@code {연도}-{PK 6자리}} 형태로 채운다
     * (CertificateService 참고). 그래서 이 컬럼만 nullable 이다.
     */
    @Column(name = "serial_no", length = 30)
    private String serialNo;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    /** 환자별 발급이력 조회를 위해 visit 을 거치지 않고 직접 들고 있는다. */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private CertificateType type;

    /** 용도. 법정서식(진단서)의 필수 기재 항목이다. */
    @Column(name = "purpose", length = 200)
    private String purpose;

    /** 제출처 (보험사·회사·상급병원 등). */
    @Column(name = "submit_to", length = 200)
    private String submitTo;

    /** 발급 버튼을 누른 사람. 문서에 서명되는 의사와 다를 수 있다(처방전 재출력 등). */
    @Column(name = "issued_by", nullable = false)
    private Long issuedBy;

    /**
     * 문서에 서명되는 의사 성명 스냅샷. 계정이 나중에 수정·삭제돼도
     * 발급 당시의 책임 주체는 그대로 남아야 한다.
     */
    @Column(name = "issuer_name", nullable = false, length = 50)
    private String issuerName;

    @Column(name = "issuer_license", length = 50)
    private String issuerLicense;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    /** 발급 시점 문서 필드 전체 스냅샷. 재발급 시 이 값을 그대로 출력한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false)
    private CertificateDocument content;

    /** LLM이 만든 초안 원문 (의사 수정 전). 무엇이 자동 생성이었는지 사후에 구분하기 위해 따로 둔다. */
    @Column(name = "ai_draft", columnDefinition = "TEXT")
    private String aiDraft;

    @Column(name = "ai_model", length = 100)
    private String aiModel;

    /** 의사가 초안을 수정했으면 true. */
    @Column(name = "ai_edited")
    private Boolean aiEdited;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CertificateStatus status = CertificateStatus.ISSUED;

    @Column(name = "void_reason", length = 300)
    private String voidReason;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    /** 재발급이면 원본 증명서 ID. 최초 발급이면 null. */
    @Column(name = "reissue_of")
    private Long reissueOf;

    /**
     * 발급번호 부여. PK가 확정된 뒤 한 번만 호출된다.
     * 채번 테이블이나 {@code MAX()+1} 을 쓰지 않는 이유는 동시 발급 시 번호가 겹치기 때문이다.
     */
    public void assignSerialNo() {
        if (id == null) {
            throw new IllegalStateException("발급번호는 저장 후에만 부여할 수 있습니다.");
        }
        this.serialNo = "%d-%06d".formatted(issuedAt.getYear(), id);
    }

    /** 무효 처리. 이미 무효인 건을 다시 무효화하면 최초 무효 시각이 덮여 쓰이므로 막는다. */
    public void voidCertificate(String reason) {
        if (status == CertificateStatus.VOID) {
            throw new IllegalStateException("이미 무효 처리된 증명서입니다.");
        }
        this.status = CertificateStatus.VOID;
        this.voidReason = reason;
        this.voidedAt = LocalDateTime.now();
    }

    public boolean isVoided() {
        return status == CertificateStatus.VOID;
    }
}
