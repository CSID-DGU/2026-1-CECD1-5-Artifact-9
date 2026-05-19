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

        KcdDisease kcd = kcdDiseaseRepository.findById(req.kcdDiseaseId())
                .orElseThrow(() -> new NoSuchElementException("KCD 상병코드를 찾을 수 없습니다: " + req.kcdDiseaseId()));

        // 이미 처방이 있으면 덮어쓰기 (재처방)
        prescriptionRepository.findByVisitId(visitId)
                .ifPresent(existing -> prescriptionRepository.delete(existing));

        Prescription prescription = Prescription.builder()
                .visitId(visitId)
                .kcdDiseaseId(req.kcdDiseaseId())
                .analysisId(req.analysisId())
                .revisitRecommendedDate(req.revisitRecommendedDate())
                .doctorNotes(req.doctorNotes())
                .build();

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

        // visit 상태: DIAGNOSED → PRESCRIBED
        visit.markPrescribed();

        return toResponse(saved, kcd);
    }

    @Transactional(readOnly = true)
    public PrescriptionResponse get(Long visitId) {
        Prescription prescription = prescriptionRepository.findByVisitId(visitId)
                .orElseThrow(() -> new NoSuchElementException("처방 정보가 없습니다: " + visitId));

        KcdDisease kcd = kcdDiseaseRepository.findById(prescription.getKcdDiseaseId())
                .orElseThrow();

        return toResponse(prescription, kcd);
    }

    private PrescriptionResponse toResponse(Prescription p, KcdDisease kcd) {
        List<PrescriptionResponse.DetailResponse> details = p.getDetails().stream()
                .map(d -> new PrescriptionResponse.DetailResponse(
                        d.getId(), d.getDrugId(), d.getMedicineName(),
                        d.getDosage(), d.getDurationDays(), d.getNotes()))
                .toList();

        return new PrescriptionResponse(
                p.getId(), p.getVisitId(),
                kcd.getId(), kcd.getCode(), kcd.getNameKr(),
                p.getAnalysisId(),
                p.getPrescribedAt(), p.getRevisitRecommendedDate(),
                p.getDoctorNotes(), details
        );
    }
}
