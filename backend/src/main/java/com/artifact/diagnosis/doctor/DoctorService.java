package com.artifact.diagnosis.doctor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;

    @Transactional
    public DoctorResponse signup(DoctorSignupRequest request) {
        if (doctorRepository.existsByLoginId(request.loginId())) {
            throw new IllegalArgumentException("이미 사용 중인 의사 아이디입니다.");
        }

        Doctor doctor = Doctor.builder()
                .loginId(request.loginId())
                .password(request.password())
                .name(request.name())
                .licenseNumber(blankToNull(request.licenseNumber()))
                .department(blankToNull(request.department()))
                .build();

        return DoctorResponse.from(doctorRepository.save(doctor));
    }

    @Transactional(readOnly = true)
    public DoctorResponse login(DoctorLoginRequest request) {
        Doctor doctor = doctorRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new IllegalArgumentException("아이디와 비밀번호가 올바르지 않습니다."));

        if (!doctor.getPassword().equals(request.password())) {
            throw new IllegalArgumentException("아이디와 비밀번호가 올바르지 않습니다.");
        }

        return DoctorResponse.from(doctor);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
