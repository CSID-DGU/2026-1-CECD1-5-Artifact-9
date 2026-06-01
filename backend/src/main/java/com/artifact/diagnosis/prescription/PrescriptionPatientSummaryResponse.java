package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.visit.VisitStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PrescriptionPatientSummaryResponse(
        Long prescriptionId,
        Long visitId,
        Long patientId,
        String patientName,
        LocalDate birthDate,
        Gender gender,
        LocalDateTime visitDate,
        VisitStatus status,
        Long doctorId,
        String doctorName,
        LocalDateTime prescribedAt
) {
}
