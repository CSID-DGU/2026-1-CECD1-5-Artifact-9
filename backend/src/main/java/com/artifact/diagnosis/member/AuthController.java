package com.artifact.diagnosis.member;

import com.artifact.diagnosis.common.security.PublicEndpoint;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@PublicEndpoint // 로그인/가입은 토큰을 <b>받기 위한</b> 경로다. 인증을 요구하면 아무도 로그인할 수 없다.
public class AuthController {

    private final MemberService memberService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse signup(@Valid @RequestBody MemberSignupRequest request) {
        return memberService.signup(request);
    }

    @PostMapping("/login")
    public MemberResponse login(@Valid @RequestBody MemberLoginRequest request) {
        return memberService.login(request);
    }
}
