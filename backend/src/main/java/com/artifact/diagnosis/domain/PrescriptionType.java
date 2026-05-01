package com.artifact.diagnosis.domain;

/**
 * 처방/처방템플릿의 종류. DB ENUM 과 1:1.
 */
public enum PrescriptionType {
    MEDICATION,    // 경구약
    TOPICAL,       // 외용제
    INJECTION,     // 주사
    PROCEDURE,     // 시술
    OBSERVATION,   // 경과관찰
    REFERRAL       // 의뢰/전원
}
