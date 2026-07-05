package com.artifact.diagnosis.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberSignupRequest(
        @NotBlank @Size(max = 50) String loginId,
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 50) String licenseNumber,
        @Size(max = 100) String department,
        MemberRole role
) {
    public MemberRole resolvedRole() {
        return role != null ? role : MemberRole.DOCTOR;
    }
}
