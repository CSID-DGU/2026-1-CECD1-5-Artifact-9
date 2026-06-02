package com.artifact.diagnosis.member;

import jakarta.validation.constraints.NotBlank;

public record MemberLoginRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}
