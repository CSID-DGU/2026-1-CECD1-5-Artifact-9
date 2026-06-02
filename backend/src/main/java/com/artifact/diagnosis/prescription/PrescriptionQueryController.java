package com.artifact.diagnosis.prescription;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/prescriptions")
@RequiredArgsConstructor
public class PrescriptionQueryController {

    private final PrescriptionService prescriptionService;

    @GetMapping("/doctor-patients")
    public List<PrescriptionPatientSummaryResponse> findDoctorPatients(
            @RequestParam Long doctorId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return prescriptionService.findPatientsByDoctorAndDate(doctorId, from, to);
    }
}
