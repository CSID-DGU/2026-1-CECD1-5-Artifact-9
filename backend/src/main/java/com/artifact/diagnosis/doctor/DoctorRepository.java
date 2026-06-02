package com.artifact.diagnosis.doctor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    boolean existsByLoginId(String loginId);

    Optional<Doctor> findByLoginId(String loginId);
}
