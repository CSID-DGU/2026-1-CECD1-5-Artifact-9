package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.member.Member;
import com.artifact.diagnosis.member.MemberRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 처방 저장/조회 서비스.
 * 같은 Visit 에 처방이 이미 있으면 기존 처방을 삭제하고 재처방한다.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final KcdDiseaseRepository kcdDiseaseRepository;
    private final VisitRepository visitRepository;
    private final MemberRepository memberRepository;

    /** 처방 저장. 저장 후 Visit 상태를 PRESCRIBED 로 전이한다. */
    @Transactional
    public PrescriptionResponse save(Long visitId, PrescriptionRequest req) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(() -> new NoSuchElementException("회원을 찾을 수 없습니다: " + req.memberId()));

        // 이미 처방이 있으면 덮어쓰기 (재처방)
        prescriptionRepository.findByVisitId(visitId)
                .ifPresent(prescriptionRepository::delete);

        Prescription prescription = Prescription.builder()
                .visitId(visitId)
                .memberId(member.getId())
                .memberName(member.getName())
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

    /** 처방 단건 조회. 없으면 NoSuchElementException → GlobalExceptionHandler 가 404 반환. */
    @Transactional(readOnly = true)
    public PrescriptionResponse get(Long visitId) {
        Prescription prescription = prescriptionRepository.findByVisitId(visitId)
                .orElseThrow(() -> new NoSuchElementException("처방 정보가 없습니다: " + visitId));

        return toResponse(prescription);
    }

    private PrescriptionResponse toResponse(Prescription p) {
        List<Long> kcdIds = p.getDiseases().stream()
                .map(PrescriptionDisease::getKcdDiseaseId)
                .toList();
        Map<Long, KcdDisease> kcdMap = kcdDiseaseRepository.findAllById(kcdIds).stream()
                .collect(Collectors.toMap(KcdDisease::getId, Function.identity()));

        List<PrescriptionResponse.DiseaseResponse> diseases = p.getDiseases().stream()
                .map(d -> {
                    KcdDisease kcd = kcdMap.get(d.getKcdDiseaseId());
                    if (kcd == null) throw new NoSuchElementException("KCD 코드를 찾을 수 없습니다: " + d.getKcdDiseaseId());
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
                p.getId(), p.getVisitId(), p.getMemberId(), p.getMemberName(), diseases,
                p.getAnalysisId(), p.getPrescribedAt(),
                p.getRevisitRecommendedDate(), p.getDoctorNotes(), details
        );
    }

    /** 의사 ID와 날짜 범위로 처방 환자 목록 조회 (진료 조회 화면용). */
    @Transactional(readOnly = true)
    public List<PrescriptionPatientSummaryResponse> findPatientsByDoctorAndDate(
            Long doctorId, java.time.LocalDate from, java.time.LocalDate to) {
        java.time.LocalDateTime start = from.atStartOfDay();
        java.time.LocalDateTime end = to.plusDays(1).atStartOfDay();
        return prescriptionRepository.findPatientSummariesByMemberIdAndVisitDateRange(doctorId, start, end);
    }
}
