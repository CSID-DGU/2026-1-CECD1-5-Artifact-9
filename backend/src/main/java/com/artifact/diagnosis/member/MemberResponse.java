package com.artifact.diagnosis.member;

public record MemberResponse(
        Long memberId,
        String loginId,
        String name,
        String licenseNumber,
        String department,
        String role,
        String token
) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getLoginId(),
                member.getName(),
                member.getLicenseNumber(),
                member.getDepartment(),
                member.getRole().name(),
                null
        );
    }

    public static MemberResponse from(Member member, String token) {
        return new MemberResponse(
                member.getId(),
                member.getLoginId(),
                member.getName(),
                member.getLicenseNumber(),
                member.getDepartment(),
                member.getRole().name(),
                token
        );
    }
}
