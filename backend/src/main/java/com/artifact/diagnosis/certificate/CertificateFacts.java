package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.member.Member;
import com.artifact.diagnosis.patient.Patient;
import com.artifact.diagnosis.prescription.Prescription;
import com.artifact.diagnosis.visit.Visit;

import java.util.List;

/**
 * 한 번의 내원에서 끌어모은 사실 정보. 문서를 조립하고 발급 권한을 판단하는 재료다.
 *
 * 여기 담긴 값은 전부 DB에서 온 것이고, 이 중 어느 것도 LLM에게 만들게 하지 않는다.
 * 병명·약품·날짜가 사실이어야 하는 이유는 서류가 보험금 지급과 회사 병가의 근거가 되기 때문이다.
 *
 * @param prescription     처방이 아직 없으면 null (진료확인서는 처방 없이도 발급된다)
 * @param attendingDoctor  그 내원을 처방한 의사. 처방이 없으면 null
 */
public record CertificateFacts(
        Visit visit,
        Patient patient,
        Prescription prescription,
        Member attendingDoctor,
        List<CertificateDocument.DiseaseLine> diseases,
        List<CertificateDocument.DrugLine> drugs
) {
    public boolean hasPrescription() {
        return prescription != null;
    }
}
