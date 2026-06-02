package com.artifact.diagnosis.doctor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DoctorSignupRequest(
        @NotBlank @Size(max = 50) String loginId,
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 50) String licenseNumber,
        @Size(max = 100) String department
) {
}
