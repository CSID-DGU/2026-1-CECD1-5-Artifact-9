package com.artifact.diagnosis.doctor;

import jakarta.validation.constraints.NotBlank;

public record DoctorLoginRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}
