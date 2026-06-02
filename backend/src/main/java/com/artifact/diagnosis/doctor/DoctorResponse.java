package com.artifact.diagnosis.doctor;

public record DoctorResponse(
        Long doctorId,
        String loginId,
        String name,
        String licenseNumber,
        String department
) {
    public static DoctorResponse from(Doctor doctor) {
        return new DoctorResponse(
                doctor.getId(),
                doctor.getLoginId(),
                doctor.getName(),
                doctor.getLicenseNumber(),
                doctor.getDepartment()
        );
    }
}
