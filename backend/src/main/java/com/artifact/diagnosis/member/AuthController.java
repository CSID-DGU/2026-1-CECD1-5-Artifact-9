package com.artifact.diagnosis.member;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
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
