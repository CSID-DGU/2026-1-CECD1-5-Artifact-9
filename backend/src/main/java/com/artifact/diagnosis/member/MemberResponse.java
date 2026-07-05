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
    public static MemberResponse from(Member member) { // 토큰 없는 버전 (일반 조회용)
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

    public static MemberResponse from(Member member, String token) { // 로그인 회원가입 응답용
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
