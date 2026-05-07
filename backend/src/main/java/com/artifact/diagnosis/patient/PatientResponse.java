package com.artifact.diagnosis.patient;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 환자 응답 DTO. 엔티티를 그대로 노출하지 않고 필요한 필드만.
 */
public record PatientResponse(
        Long id,
        String name,
        LocalDate birthDate,
        Gender gender,
        String phone,
        String memo,
        LocalDateTime createdAt
) {
    public static PatientResponse from(Patient p) {
        return new PatientResponse(
                p.getId(),
                p.getName(),
                p.getBirthDate(),
                p.getGender(),
                p.getPhone(),
                p.getMemo(),
                p.getCreatedAt()
        );
    }
}
