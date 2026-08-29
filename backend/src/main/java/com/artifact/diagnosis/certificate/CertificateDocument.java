package com.artifact.diagnosis.certificate;

import java.util.List;

/**
 * 종이에 실제로 찍히는 값 전부. 발급 시점에 이대로 굳어 {@code certificate.content_json} 에 저장된다.
 *
 * 서류 5종이 필드를 공유한다. 종류마다 쓰는 칸이 다를 뿐이라 안 쓰는 칸은 null 로 둔다.
 * 종류별로 record 를 따로 두지 않은 이유는, 저장된 JSON을 다시 읽어 출력할 때
 * 종류에 따라 역직렬화 타입을 갈아끼우는 분기가 프론트·백엔드 양쪽에 생기기 때문이다.
 *
 * 여기 담긴 값은 발급 당시의 사실이다. 나중에 환자 이름이 바뀌든 처방이 수정되든
 * 이 스냅샷은 건드리지 않는다. 재발급은 이 값을 그대로 다시 렌더링하는 것이다.
 *
 * {@code patientResidentNo} 는 마스킹된 표시용 문자열이다(예: {@code 900101-1******}).
 * 환자 주민등록번호는 시스템 어디에도 저장하지 않으며, 생년월일과 성별로 계산해 문서에만 찍는다
 * ({@link ResidentNumberMask} 참고).
 */
public record CertificateDocument(

        // ── 의료기관 (application.properties 의 hospital.* 값) ──
        String hospitalName,
        String hospitalAddress,
        String hospitalPhone,
        String hospitalRegistrationNo,
        /**
         * 문서 하단에 찍히는 직인 이미지 주소. 비어 있으면 '(직인생략)' 으로 표기된다.
         * 다른 병원 정보와 마찬가지로 발급 당시 값이 스냅샷에 박힌다 — 나중에 직인을 바꿔도
         * 이미 발급된 문서는 그때 찍힌 도장을 그대로 유지해야 하기 때문이다.
         */
        String hospitalSealImageUrl,

        // ── 환자 ──
        String patientName,
        String patientResidentNo,
        String patientGender,
        String patientBirthDate,
        String patientPhone,

        // ── 진료 사실 ──
        String visitDate,
        String treatmentPeriodFrom,
        String treatmentPeriodTo,
        List<DiseaseLine> diseases,

        // ── 서술형 칸 (LLM 초안 대상) ──
        /** 진단서의 '향후 치료에 대한 소견', 소견서 본문, 의뢰서의 임상 소견. */
        String opinion,
        /** 진단서의 향후 치료계획. */
        String treatmentPlan,
        /** 진료의뢰서의 의뢰 사유. */
        String referralReason,
        /** 비고. */
        String remarks,

        // ── 진료의뢰서 전용 ──
        String referralTo,

        // ── 처방전 전용 ──
        List<DrugLine> drugs,
        /** 처방전 사용기간(발급일부터 며칠). 미기재 시 3일이 원칙이다. */
        Integer prescriptionValidDays,

        // ── 발급 정보 ──
        String purpose,
        String submitTo,
        String doctorName,
        String doctorLicenseNo,
        String department,
        String issuedDate,
        String serialNo,

        // ── 문서 하단 법적 표기 ──
        String formCode,
        String legalBasis
) {

    /** 상병 한 줄. 진단서·의뢰서·처방전이 공유한다. */
    public record DiseaseLine(String code, String name, boolean primary) {}

    /** 처방 약품 한 줄. */
    public record DrugLine(String name, String dosage, Integer durationDays, String notes) {}

    /**
     * 서술형 칸만 교체한 사본을 만든다. 의사가 LLM 초안을 고쳐 발급할 때 쓴다.
     * 사실 항목(병명·약품·날짜·면허번호)은 여기서 바꿀 수 없다 — 그게 이 메서드의 목적이다.
     */
    public CertificateDocument withNarrative(String opinion, String treatmentPlan,
                                             String referralReason, String remarks,
                                             String referralTo) {
        return new CertificateDocument(
                hospitalName, hospitalAddress, hospitalPhone, hospitalRegistrationNo,
                hospitalSealImageUrl,
                patientName, patientResidentNo, patientGender, patientBirthDate, patientPhone,
                visitDate, treatmentPeriodFrom, treatmentPeriodTo, diseases,
                opinion, treatmentPlan, referralReason, remarks,
                referralTo,
                drugs, prescriptionValidDays,
                purpose, submitTo, doctorName, doctorLicenseNo, department, issuedDate, serialNo,
                formCode, legalBasis
        );
    }

    /** 발급번호는 저장 후에야 정해진다. 스냅샷에도 같은 번호가 박혀야 재발급본이 원본과 일치한다. */
    public CertificateDocument withSerialNo(String serialNo) {
        return new CertificateDocument(
                hospitalName, hospitalAddress, hospitalPhone, hospitalRegistrationNo,
                hospitalSealImageUrl,
                patientName, patientResidentNo, patientGender, patientBirthDate, patientPhone,
                visitDate, treatmentPeriodFrom, treatmentPeriodTo, diseases,
                opinion, treatmentPlan, referralReason, remarks,
                referralTo,
                drugs, prescriptionValidDays,
                purpose, submitTo, doctorName, doctorLicenseNo, department, issuedDate, serialNo,
                formCode, legalBasis
        );
    }
}
