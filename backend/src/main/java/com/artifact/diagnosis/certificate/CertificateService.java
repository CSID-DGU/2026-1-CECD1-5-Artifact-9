package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.common.jwt.AuthPrincipal;
import com.artifact.diagnosis.member.Member;
import com.artifact.diagnosis.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 제증명 발급·조회·재발급·무효 처리.
 *
 * 이 서비스가 지키는 규칙은 셋이다.
 *
 *   - 의료법 제17조 — 진단명이 들어가는 서류는 직접 진찰한 의사만 낼 수 있다.
 *       로그인한 사람이 그 내원의 처방을 작성한 본인인지 확인한다. 같은 병원 의사라도 남의 환자
 *       진단서를 대신 떼줄 수는 없다.
 *   - 사실은 서버가 채운다 — 병명·약품·날짜·면허번호는 요청에서 받지 않고 DB에서 조립한다.
 *   - 발급한 것은 지우지 않는다 — 잘못 나간 서류도 행을 삭제하지 않고 무효 표시만 한다.
 *       발급대장에서 기록이 사라지면 그 서류가 존재했다는 사실 자체를 확인할 수 없게 된다.
 */
@Service
@RequiredArgsConstructor
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final CertificateDataAssembler assembler;
    private final CertificateDraftService draftService;
    private final MemberRepository memberRepository;

    /** LLM 초안 생성. 문서를 만들지도, 저장하지도 않는다 — 화면 입력칸을 미리 채워줄 뿐이다. */
    @Transactional(readOnly = true)
    public CertificateDraftResponse draft(Long visitId, AuthPrincipal principal, CertificateDraftRequest req) {
        CertificateFacts facts = assembler.loadFacts(visitId);
        requirePrescriptionIfNeeded(facts, req.type());
        verifyIssuePermission(facts, req.type(), principal);
        return draftService.draft(facts, req);
    }

    /**
     * 발급. 저장 직후 발급번호를 부여하고 스냅샷에도 같은 번호를 박는다 —
     * 재발급본이 원본과 한 글자도 다르지 않게 나오려면 번호까지 스냅샷 안에 있어야 한다.
     */
    @Transactional
    public CertificateResponse issue(Long visitId, AuthPrincipal principal, CertificateIssueRequest req) {
        CertificateType type = req.type();
        CertificateFacts facts = assembler.loadFacts(visitId);

        requirePrescriptionIfNeeded(facts, type);
        verifyIssuePermission(facts, type, principal);

        Member signer = resolveSigner(facts, type, principal);
        CertificateDocument content = assembler.assemble(facts, type, signer, req);

        Certificate certificate = Certificate.builder()
                .visitId(visitId)
                .patientId(facts.patient().getId())
                .type(type)
                .purpose(req.purpose())
                .submitTo(req.submitTo())
                .issuedBy(principal.memberId())
                .issuerName(signer.getName())
                .issuerLicense(signer.getLicenseNumber())
                .content(content)
                .aiDraft(req.aiDraft())
                .aiModel(req.aiModel())
                .aiEdited(req.aiEdited())
                .status(CertificateStatus.ISSUED)
                .build();

        // 발급번호는 PK에서 만든다. 채번 테이블이나 MAX()+1 은 동시 발급 시 번호가 겹친다.
        Certificate saved = certificateRepository.saveAndFlush(certificate);
        saved.assignSerialNo();
        saved.setContent(saved.getContent().withSerialNo(saved.getSerialNo()));

        return CertificateResponse.from(saved);
    }

    /**
     * 재발급. 환자가 서류를 분실했을 때 쓴다.
     *
     * 지금 데이터로 다시 만들지 않고 원본 스냅샷을 그대로 복사한다. 그 사이 처방이 수정됐다면
     * 새로 만든 서류는 이미 나간 종이와 내용이 달라지고, 그러면 어느 쪽이 진짜인지 알 수 없게 된다.
     * 발급번호만 새로 받고 {@code reissueOf} 로 원본을 가리킨다.
     */
    @Transactional
    public CertificateResponse reissue(Long certificateId, AuthPrincipal principal) {
        Certificate origin = findEntity(certificateId);

        if (origin.isVoided()) {
            throw new IllegalStateException("무효 처리된 증명서는 재발급할 수 없습니다.");
        }

        CertificateFacts facts = assembler.loadFacts(origin.getVisitId());
        verifyIssuePermission(facts, origin.getType(), principal);

        Certificate copy = Certificate.builder()
                .visitId(origin.getVisitId())
                .patientId(origin.getPatientId())
                .type(origin.getType())
                .purpose(origin.getPurpose())
                .submitTo(origin.getSubmitTo())
                .issuedBy(principal.memberId())
                // 서명은 원본과 같은 의사 이름으로 나간다. 재출력한 사람이 서명 주체가 되면 안 된다.
                .issuerName(origin.getIssuerName())
                .issuerLicense(origin.getIssuerLicense())
                .content(origin.getContent())
                .aiDraft(origin.getAiDraft())
                .aiModel(origin.getAiModel())
                .aiEdited(origin.getAiEdited())
                .status(CertificateStatus.ISSUED)
                .reissueOf(origin.getId())
                .build();

        Certificate saved = certificateRepository.saveAndFlush(copy);
        saved.assignSerialNo();
        saved.setContent(saved.getContent().withSerialNo(saved.getSerialNo()));

        return CertificateResponse.from(saved);
    }

    /** 무효 처리. 행을 지우지 않고 상태만 바꾼다. */
    @Transactional
    public CertificateResponse voidCertificate(Long certificateId, CertificateVoidRequest req) {
        Certificate certificate = findEntity(certificateId);
        certificate.voidCertificate(req.reason());
        return CertificateResponse.from(certificate);
    }

    @Transactional(readOnly = true)
    public CertificateResponse get(Long certificateId) {
        return CertificateResponse.from(findEntity(certificateId));
    }

    /** 환자별 발급이력 — 증명 탭의 발급대장. */
    @Transactional(readOnly = true)
    public List<CertificateSummaryResponse> findByPatient(Long patientId) {
        return certificateRepository.findByPatientIdOrderByIssuedAtDesc(patientId).stream()
                .map(CertificateSummaryResponse::from)
                .toList();
    }

    /** 특정 내원에서 발급된 서류들. */
    @Transactional(readOnly = true)
    public List<CertificateSummaryResponse> findByVisit(Long visitId) {
        return certificateRepository.findByVisitIdOrderByIssuedAtDesc(visitId).stream()
                .map(CertificateSummaryResponse::from)
                .toList();
    }

    private Certificate findEntity(Long certificateId) {
        return certificateRepository.findById(certificateId)
                .orElseThrow(() -> new NoSuchElementException("증명서를 찾을 수 없습니다: " + certificateId));
    }

    /**
     * 의료법 제17조 확인.
     *
     * 컨트롤러의 {@code @StaffAccess} 는 "이 화면에 들어올 수 있는가"까지만 본다.
     * 진단명이 들어가는 서류는 거기서 한 단계 더 좁혀야 한다 — 직접 진찰한 의사 본인인지.
     * 이 검사를 컨트롤러 애노테이션으로 못 옮기는 이유는, 발급 가능 여부가 요청 body의
     * {@code type} 과 그 내원의 담당의가 누구인지에 따라 갈리기 때문이다.
     */
    private void verifyIssuePermission(CertificateFacts facts, CertificateType type, AuthPrincipal principal) {
        if (!type.isDoctorOnly()) {
            return;
        }
        Long attendingId = facts.prescription().getMemberId();
        if (!attendingId.equals(principal.memberId())) {
            String attendingName = facts.attendingDoctor() == null
                    ? "확인 불가" : facts.attendingDoctor().getName();
            throw new AccessDeniedException(
                    "%s는 해당 환자를 직접 진료한 의사만 발급할 수 있습니다(의료법 제17조). 담당의: %s"
                            .formatted(type.getLabel(), attendingName));
        }
    }

    /**
     * 진료확인서를 뺀 나머지는 진료 내용(상병·처방)이 확정돼야 발급할 수 있다.
     * 진료확인서만 예외인 이유는 진단명 없이 "언제 내원했다"만 확인해주는 서류이기 때문이다.
     */
    private void requirePrescriptionIfNeeded(CertificateFacts facts, CertificateType type) {
        if (type == CertificateType.TREATMENT_CONFIRMATION) {
            return;
        }
        if (!facts.hasPrescription()) {
            throw new IllegalStateException(
                    "진료(처방)가 확정되지 않아 " + type.getLabel() + "을(를) 발급할 수 없습니다.");
        }
    }

    /**
     * 문서에 서명될 의사를 정한다.
     *
     * 발급 버튼을 누른 사람과 다를 수 있다. 원무과 직원이 처방전을 재출력하거나 진료확인서를
     * 발급해도, 종이에 이름과 면허번호가 찍히는 사람은 실제로 진료한 의사여야 한다.
     * 담당의를 특정할 수 없을 때만(진료확인서 + 처방 없음) 요청자 이름으로 나간다.
     */
    private Member resolveSigner(CertificateFacts facts, CertificateType type, AuthPrincipal principal) {
        if (type.isDoctorOnly()) {
            // 여기 오면 요청자 == 담당의임이 verifyIssuePermission 에서 이미 확인됐다.
            return requester(principal);
        }
        return facts.attendingDoctor() != null ? facts.attendingDoctor() : requester(principal);
    }

    private Member requester(AuthPrincipal principal) {
        return memberRepository.findById(principal.memberId())
                .orElseThrow(() -> new NoSuchElementException("회원을 찾을 수 없습니다: " + principal.memberId()));
    }
}
