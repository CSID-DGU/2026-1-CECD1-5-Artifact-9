package com.artifact.diagnosis.member;

import com.artifact.diagnosis.common.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 가입/로그인 서비스.
 * 비밀번호는 BCrypt 해시로 저장하며, 로그인 성공 시 JWT 토큰을 발급한다.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public MemberResponse signup(MemberSignupRequest request) {
        if (memberRepository.existsByLoginId(request.loginId())) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }

        Member member = Member.builder()
                .loginId(request.loginId())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .licenseNumber(blankToNull(request.licenseNumber()))
                .department(blankToNull(request.department()))
                .role(request.resolvedRole())
                .build();

        Member saved = memberRepository.save(member);
        String token = jwtUtil.generate(saved.getId(), saved.getLoginId(), saved.getRole().name());
        return MemberResponse.from(saved, token);
    }

    @Transactional(readOnly = true)
    public MemberResponse login(MemberLoginRequest request) {
        Member member = memberRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new IllegalArgumentException("아이디와 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new IllegalArgumentException("아이디와 비밀번호가 올바르지 않습니다.");
        }

        String token = jwtUtil.generate(member.getId(), member.getLoginId(), member.getRole().name());
        return MemberResponse.from(member, token);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
