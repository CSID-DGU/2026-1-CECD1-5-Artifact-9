package com.artifact.diagnosis.patient;

import java.time.LocalDate;

/**
 * 접수 화면용 환자 검색 응답 DTO.
 * 동명이인 구분에 필요한 필드 + 전화번호 마스킹 + 최종진료일 포함.
 */
public record PatientSearchResponse(
        Long patientId,
        String name,
        LocalDate birthDate,
        Gender gender,
        String phone,
        String maskedPhone,
        LocalDate lastVisitDate
) {
    public static PatientSearchResponse from(Patient p, LocalDate lastVisitDate) {
        return new PatientSearchResponse(
                p.getId(),
                p.getName(),
                p.getBirthDate(),
                p.getGender(),
                p.getPhone(),
                maskPhone(p.getPhone()),
                lastVisitDate
        );
    }

    /** 중간 자리 마스킹: 010-1234-5678 → 010-****-5678 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return phone.replaceAll("(\\d{3})[-]?(\\d{3,4})[-]?(\\d{4})", "$1-****-$3");
    }
}
