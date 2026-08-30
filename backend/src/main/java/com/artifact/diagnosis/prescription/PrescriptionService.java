package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.common.util.PiiMasker;
import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.member.Member;
import com.artifact.diagnosis.member.MemberRepository;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
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
    private final PatientRepository patientRepository;

    /**
     * AI 처방 코멘트 프롬프트에 넣을 접수 메모를 만든다. 식별정보는 지운다.
     *
     * 메모를 요청 body 로 받지 않고 여기서 다시 읽는 이유가 두 가지다.
     *   - 이름을 지우려면 그 환자의 이름을 알아야 하는데, 그건 DB 에만 있다.
     *   - 클라이언트가 보낸 문자열을 그대로 프롬프트에 넣으면 내용도 대상도 검증되지 않는다.
     *     visitId 는 이미 경로에 있으니 서버가 직접 읽는 편이 짧고 확실하다.
     *
     * 메모가 없거나 마스킹 후 빈 문자열이면 null 을 돌려준다 — 프롬프트에서 줄째로 빠진다.
     */
    @Transactional(readOnly = true)
    public String maskedReceptionMemo(Long visitId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));

        // 환자를 못 찾아도 코멘트 생성 자체를 막지는 않는다. 이름만 못 지울 뿐
        // 연락처·주민번호·이메일은 이름 없이도 지워진다.
        String patientName = patientRepository.findById(visit.getPatientId())
                .map(Patient::getName)
                .orElse(null);

        String masked = PiiMasker.mask(visit.getReceptionMemo(), patientName);
        return (masked == null || masked.isBlank()) ? null : masked;
    }

    /**
     * 처방 저장. 저장 후 Visit 상태를 PRESCRIBED 로 전이한다.
     *
     * @param doctorId 처방 작성자. 반드시 인증 토큰에서 꺼낸 값이어야 한다(컨트롤러의 {@code @AuthenticationPrincipal}).
     *                 요청 body에서 받으면 아무나 남의 이름으로 처방을 남길 수 있고, 그 기록은 진료기록부의
     *                 법적 책임 주체가 된다 — 위조된 뒤에는 사후 감사로도 되돌릴 수 없다.
     */
    @Transactional
    public PrescriptionResponse save(Long visitId, Long doctorId, PrescriptionRequest req) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));
        Member member = memberRepository.findById(doctorId)
                .orElseThrow(() -> new NoSuchElementException("회원을 찾을 수 없습니다: " + doctorId));

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
                .aiComment(req.aiComment())
                .aiCommentModel(req.aiCommentModel())
                .aiCommentGeneratedAt(req.aiCommentGeneratedAt())
                .aiCommentEdited(req.aiCommentEdited())
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
        visit.confirmDiagnosisAndPrescribe();

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
                p.getRevisitRecommendedDate(), p.getDoctorNotes(),
                p.getAiComment(), p.getAiCommentModel(),
                p.getAiCommentGeneratedAt(), p.getAiCommentEdited(),
                details
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
