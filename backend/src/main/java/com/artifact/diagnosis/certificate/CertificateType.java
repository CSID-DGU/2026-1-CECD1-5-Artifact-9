package com.artifact.diagnosis.certificate;

import lombok.Getter;

import java.util.List;

/**
 * 발급 가능한 제증명 종류.
 *
 * 병원에서 떼주는 서류는 크게 법정서식과 비법정서식으로 나뉜다.
 * 법정서식은 법령이 서식 자체를 정해둔 것이라 항목을 임의로 빼거나 바꿀 수 없고,
 * 비법정서식은 병원이 자체 양식으로 만든다. 어느 쪽인지에 따라 화면에 표기해야 할
 * 근거 조문과 서식번호가 달라지므로 종류마다 함께 들고 다닌다.
 *
 * {@link #doctorOnly} 는 "누가 발급 버튼을 누를 수 있는가"다. 진단명이 들어가는 서류는
 * 의료법 제17조에 따라 직접 진찰한 의사만 낼 수 있다. 반면 진료확인서처럼 진단명 없이
 * "언제 왔다 갔다"만 확인해주는 서류는 실제 병원에서도 원무과가 발급한다.
 *
 * {@link #aiFields} 는 LLM에게 초안을 맡길 서술형 칸의 이름이다. 병명·KCD코드·약품명·
 * 날짜·면허번호 같은 사실 항목은 이 목록에 절대 넣지 않는다 — 그런 값은 DB에서 그대로 채워야지
 * 모델이 지어내면 그 자체로 허위진단서가 된다.
 */
@Getter
public enum CertificateType {

    /** 처방전 — 의료법 시행규칙 제12조 별지 제9호서식. 이미 확정된 처방을 종이로 옮기는 것이라 AI가 손댈 칸이 없다. */
    PRESCRIPTION(
            "처방전",
            true,
            "의료법 시행규칙 제12조",
            "별지 제9호서식",
            false,
            List.of()
    ),

    /** 진단서 — 의료법 시행규칙 제9조제1항 별지 제5호의2서식. '향후 치료에 대한 소견'이 서술 칸이다. */
    DIAGNOSIS(
            "진단서",
            true,
            "의료법 시행규칙 제9조제1항",
            "별지 제5호의2서식",
            true,
            List.of("opinion", "treatmentPlan")
    ),

    /** 진료확인서 — 비법정. 진단명이 없어 원무과가 발급한다. 회사 제출·보험 청구용. */
    TREATMENT_CONFIRMATION(
            "진료확인서",
            false,
            null,
            null,
            false,
            List.of()
    ),

    /** 소견서 — 비법정이지만 진단 소견이 들어가므로 의사 전용. 본문 전체가 서술 칸이다. */
    MEDICAL_OPINION(
            "소견서",
            false,
            "의료법 제17조",
            null,
            true,
            List.of("opinion")
    ),

    /** 진료의뢰서 — 국민건강보험 요양급여의 기준에 관한 규칙 별지 제4호서식. 상급병원 회송용. */
    REFERRAL(
            "진료의뢰서",
            true,
            "국민건강보험 요양급여의 기준에 관한 규칙",
            "별지 제4호서식",
            true,
            List.of("referralReason", "opinion")
    );

    /** 화면과 문서 제목에 찍히는 한글 명칭. */
    private final String label;

    /** 법정서식이면 true. 문서 하단에 서식번호를 표기할지 결정한다. */
    private final boolean statutory;

    /** 근거 법령. 비법정서식은 null 일 수 있다. */
    private final String legalBasis;

    /** 별지 서식번호. 비법정서식은 null. */
    private final String formCode;

    /** 의료법 제17조 대상 — 직접 진찰한 의사만 발급 가능하면 true. */
    private final boolean doctorOnly;

    /** LLM이 초안을 채울 서술형 필드 이름들. 사실 항목은 여기 넣지 않는다. */
    private final List<String> aiFields;

    CertificateType(String label, boolean statutory, String legalBasis,
                    String formCode, boolean doctorOnly, List<String> aiFields) {
        this.label = label;
        this.statutory = statutory;
        this.legalBasis = legalBasis;
        this.formCode = formCode;
        this.doctorOnly = doctorOnly;
        this.aiFields = aiFields;
    }

    /** LLM 초안을 지원하는 종류인지. */
    public boolean isAiAssisted() {
        return !aiFields.isEmpty();
    }
}
