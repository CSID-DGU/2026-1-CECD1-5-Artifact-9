package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final KcdDiseaseRepository kcdDiseaseRepository;
    private final VisitRepository visitRepository;

    @Transactional
    public PrescriptionResponse save(Long visitId, PrescriptionRequest req) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));

        // 이미 처방이 있으면 덮어쓰기 (재처방)
        prescriptionRepository.findByVisitId(visitId)
                .ifPresent(prescriptionRepository::delete);

        Prescription prescription = Prescription.builder()
                .visitId(visitId)
                .analysisId(req.analysisId())
                .revisitRecommendedDate(req.revisitRecommendedDate())
                .doctorNotes(req.doctorNotes())
                .build();

        req.diseases().forEach(d -> prescription.addDisease(
                PrescriptionDisease.builder()
                        .kcdDiseaseId(d.kcdDiseaseId())
                        .primary(d.isPrimary())
                        .build()
        ));

        req.details().forEach(d -> prescription.addDetail(
                PrescriptionDetail.builder()
                        .drugId(d.drugId())
                        .medicineName(d.medicineName())
                        .dosage(d.dosage())
                        .durationDays(d.durationDays())
                        .notes(d.notes())
                        .build()
        ));

        Prescription saved = prescriptionRepository.save(prescription);
        visit.markPrescribed();

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PrescriptionResponse get(Long visitId) {
        Prescription prescription = prescriptionRepository.findByVisitId(visitId)
                .orElseThrow(() -> new NoSuchElementException("처방 정보가 없습니다: " + visitId));

        return toResponse(prescription);
    }

    private PrescriptionResponse toResponse(Prescription p) {
        List<PrescriptionResponse.DiseaseResponse> diseases = p.getDiseases().stream()
                .map(d -> {
                    KcdDisease kcd = kcdDiseaseRepository.findById(d.getKcdDiseaseId()).orElseThrow();
                    return new PrescriptionResponse.DiseaseResponse(
                            d.getKcdDiseaseId(), kcd.getCode(), kcd.getNameKr(), d.isPrimary());
                })
                .toList();

        List<PrescriptionResponse.DetailResponse> details = p.getDetails().stream()
                .map(d -> new PrescriptionResponse.DetailResponse(
                        d.getId(), d.getDrugId(), d.getMedicineName(),
                        d.getDosage(), d.getDurationDays(), d.getNotes()))
                .toList();

        return new PrescriptionResponse(
                p.getId(), p.getVisitId(), diseases,
                p.getAnalysisId(), p.getPrescribedAt(),
                p.getRevisitRecommendedDate(), p.getDoctorNotes(), details
        );
    }
}
