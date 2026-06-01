package com.artifact.diagnosis.doctor;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final DoctorService doctorService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public DoctorResponse signup(@Valid @RequestBody DoctorSignupRequest request) {
        return doctorService.signup(request);
    }

    @PostMapping("/login")
    public DoctorResponse login(@Valid @RequestBody DoctorLoginRequest request) {
        return doctorService.login(request);
    }
}
