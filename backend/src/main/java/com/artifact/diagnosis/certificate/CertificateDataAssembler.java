package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.disease.KcdDisease;
import com.artifact.diagnosis.disease.KcdDiseaseRepository;
import com.artifact.diagnosis.member.Member;
import com.artifact.diagnosis.member.MemberRepository;
import com.artifact.diagnosis.patient.Gender;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.patient.PatientRepository;
import com.artifact.diagnosis.prescription.Prescription;
import com.artifact.diagnosis.prescription.PrescriptionDisease;
import com.artifact.diagnosis.prescription.PrescriptionRepository;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 내원 한 건을 문서 한 장으로 옮기는 조립기.
 *
 * <p>서류마다 채우는 칸이 다르지만 재료는 같다 — 환자, 내원일, 상병, 처방, 담당의.
 * 그래서 재료를 모으는 일({@link #loadFacts})과 칸에 배치하는 일({@link #assemble})을 나눴다.
 * 발급 권한 판단(직접 진찰한 의사인가)에도 같은 재료가 필요하기 때문이다.
 *
 * <p>날짜는 ISO 형식({@code yyyy-MM-dd}) 문자열로 넣는다. 화면에 "2026년 8월 28일"로 보이는 것은
 * 프론트 문서 컴포넌트의 몫이다. 스냅샷에는 값만 굳혀두고 표현은 분리해야, 나중에 서식이 바뀌어도
 * 이미 발급된 서류의 내용이 흔들리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CertificateDataAssembler {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 처방전 사용기간. 미기재 시 3일이 원칙이다(의료법 시행규칙 제12조제2항). */
    private static final int DEFAULT_PRESCRIPTION_VALID_DAYS = 3;

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final KcdDiseaseRepository kcdDiseaseRepository;
    private final MemberRepository memberRepository;
    private final HospitalProperties hospital;

    /**
     * 내원 한 건의 사실 정보를 모은다.
     *
     * <p>처방이 없어도 예외로 던지지 않는다. 진료확인서는 "왔다 갔다"만 확인해주는 서류라
     * 처방 없이도 발급되기 때문이다. 처방이 꼭 있어야 하는 종류인지는 서비스가 판단한다.
     */
    @Transactional(readOnly = true)
    public CertificateFacts loadFacts(Long visitId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수를 찾을 수 없습니다: " + visitId));
        Patient patient = patientRepository.findById(visit.getPatientId())
                .orElseThrow(() -> new NoSuchElementException("환자를 찾을 수 없습니다: " + visit.getPatientId()));

        Prescription prescription = prescriptionRepository.findByVisitId(visitId).orElse(null);
        if (prescription == null) {
            return new CertificateFacts(visit, patient, null, null, List.of(), List.of());
        }

        Member attending = memberRepository.findById(prescription.getMemberId()).orElse(null);

        return new CertificateFacts(
                visit, patient, prescription, attending,
                toDiseaseLines(prescription),
                toDrugLines(prescription)
        );
    }

    /**
     * 사실 정보 + 의사가 확정한 서술 내용을 문서 한 장으로 합친다.
     *
     * @param signer 문서에 서명되는 의사. 발급 버튼을 누른 사람과 다를 수 있다 —
     *               처방전을 원무과가 재출력해도 서명은 처방한 의사 이름으로 나가야 한다.
     */
    public CertificateDocument assemble(CertificateFacts facts, CertificateType type,
                                        Member signer, CertificateIssueRequest req) {
        Patient patient = facts.patient();
        LocalDate visitDate = facts.visit().getVisitDate().toLocalDate();

        boolean isPrescription = type == CertificateType.PRESCRIPTION;

        return new CertificateDocument(
                hospital.getName(),
                hospital.getAddress(),
                hospital.getPhone(),
                hospital.getRegistrationNo(),
                hospital.getSealImageUrl(),

                patient.getName(),
                ResidentNumberMask.of(patient.getBirthDate(), patient.getGender()),
                genderLabel(patient.getGender()),
                patient.getBirthDate() == null ? null : patient.getBirthDate().format(ISO_DATE),
                patient.getPhone(),

                visitDate.format(ISO_DATE),
                visitDate.format(ISO_DATE),
                treatmentPeriodTo(facts, visitDate),
                facts.diseases(),

                req.opinion(),
                req.treatmentPlan(),
                req.referralReason(),
                req.remarks(),

                req.referralTo(),

                isPrescription ? facts.drugs() : null,
                isPrescription ? validDays(req.prescriptionValidDays()) : null,

                req.purpose(),
                req.submitTo(),
                signer.getName(),
                signer.getLicenseNumber(),
                signer.getDepartment(),
                LocalDate.now().format(ISO_DATE),
                null,   // 발급번호는 저장 후 채운다 (CertificateDocument#withSerialNo)

                type.getFormCode(),
                type.getLegalBasis()
        );
    }

    /**
     * 치료기간 종료일. 의사가 재진 예정일을 남겼으면 그날까지로 본다 —
     * "언제까지 치료가 필요한가"는 회사 병가와 보험 청구가 실제로 보는 값이라
     * 근거 없이 늘려 잡지 않고 진료기록에 남은 날짜만 쓴다.
     */
    private String treatmentPeriodTo(CertificateFacts facts, LocalDate visitDate) {
        if (facts.hasPrescription() && facts.prescription().getRevisitRecommendedDate() != null) {
            return facts.prescription().getRevisitRecommendedDate().format(ISO_DATE);
        }
        return visitDate.format(ISO_DATE);
    }

    private int validDays(Integer requested) {
        return requested == null || requested <= 0 ? DEFAULT_PRESCRIPTION_VALID_DAYS : requested;
    }

    /** 주상병이 위로 오게 정렬한다. 진단서에는 주상병이 첫 줄에 와야 한다. */
    private List<CertificateDocument.DiseaseLine> toDiseaseLines(Prescription prescription) {
        List<Long> kcdIds = prescription.getDiseases().stream()
                .map(PrescriptionDisease::getKcdDiseaseId)
                .toList();
        Map<Long, KcdDisease> kcdMap = kcdDiseaseRepository.findAllById(kcdIds).stream()
                .collect(Collectors.toMap(KcdDisease::getId, Function.identity()));

        return prescription.getDiseases().stream()
                .sorted(Comparator.comparing(PrescriptionDisease::isPrimary).reversed())
                .map(d -> {
                    KcdDisease kcd = kcdMap.get(d.getKcdDiseaseId());
                    if (kcd == null) {
                        throw new NoSuchElementException("KCD 코드를 찾을 수 없습니다: " + d.getKcdDiseaseId());
                    }
                    return new CertificateDocument.DiseaseLine(kcd.getCode(), kcd.getNameKr(), d.isPrimary());
                })
                .toList();
    }

    private List<CertificateDocument.DrugLine> toDrugLines(Prescription prescription) {
        return prescription.getDetails().stream()
                .map(d -> new CertificateDocument.DrugLine(
                        d.getMedicineName(), d.getDosage(), d.getDurationDays(), d.getNotes()))
                .toList();
    }

    private String genderLabel(Gender gender) {
        if (gender == null) return null;
        return switch (gender) {
            case M -> "남";
            case F -> "여";
            case OTHER -> "기타";
        };
    }
}
