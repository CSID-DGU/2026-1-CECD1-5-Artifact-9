package com.artifact.diagnosis.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.EnumSet;
import java.util.Set;

public record MemberSignupRequest(
        @NotBlank @Size(max = 50) String loginId,
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 50) String licenseNumber,
        @Size(max = 100) String department,
        MemberRole role
) {
    /**
     * 자가 가입으로 지정할 수 있는 역할.
     * ADMIN 은 제외한다 — 가입 API 가 permitAll 이므로 허용하면 누구나 관리자가 된다.
     */
    private static final Set<MemberRole> SIGNUP_ALLOWED_ROLES =
            EnumSet.of(MemberRole.DOCTOR, MemberRole.NURSE, MemberRole.STAFF);

    public MemberRole resolvedRole() {
        if (role == null) {
            return MemberRole.DOCTOR;
        }
        if (!SIGNUP_ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("가입 시 지정할 수 없는 역할입니다: " + role);
        }
        return role;
    }
}
