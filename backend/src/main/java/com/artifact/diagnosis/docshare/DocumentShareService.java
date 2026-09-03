package com.artifact.diagnosis.docshare;

import com.artifact.diagnosis.certificate.Certificate;
import com.artifact.diagnosis.certificate.CertificateRepository;
import com.artifact.diagnosis.common.util.DisplayNo;
import com.artifact.diagnosis.patient.PatientService;
import com.artifact.diagnosis.prescription.PrescriptionResponse;
import com.artifact.diagnosis.prescription.PrescriptionService;
import com.artifact.diagnosis.visit.KioskTokenGenerator;
import com.artifact.diagnosis.visit.Visit;
import com.artifact.diagnosis.visit.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 감열지 QR 이 가리키는 "이 문서만 다시 보는" 링크의 발급과 열람.
 *
 * <p><b>왜 로그인 없는 링크인가.</b> 이 QR 을 찍는 사람은 방금 종이를 받아 든 환자다.
 * 병원 계정이 없으므로 {@code /main/*} 로 보내면 로그인 화면만 본다. 그래서 열람 전용
 * 공개 경로를 따로 열되, 다음 세 가지로 범위를 좁힌다.
 * <ul>
 *   <li>주소에 실리는 값은 base62 12자 난수뿐이다. 발급번호(2026-000006)처럼 순번이
 *       아니라서 앞뒤 번호로 남의 문서를 열어볼 수 없다.</li>
 *   <li>토큰 하나는 문서 한 건만 연다. 환자의 다른 진료·다른 증명서로는 이동할 수 없다.</li>
 *   <li>{@code document.share.ttl-days} 가 지나면 만료된다. 종이를 잃어버렸을 때
 *       그 QR 이 영구적인 열쇠로 남지 않게 하는 장치다.</li>
 *   <li><b>증명서는 토큰만으로 열리지 않는다.</b> 환자 생년월일을 한 번 더 맞춰야 내용이 나간다
 *       ({@link #verifyCertificate}). 종이를 잃어버렸을 때 주운 사람이 QR 만 찍어서
 *       진단명과 소견까지 읽는 일을 막는 장치다.</li>
 * </ul>
 *
 * <p><b>생년월일이 비밀번호는 아니다.</b> 종이에 이미 이름이 인쇄되어 있으니 아는 사람은 안다.
 * 다만 "주운 사람이 즉시 읽는 것"과 "환자를 알아야 읽는 것" 사이에는 실질적인 차이가 있고,
 * 환자에게 새 비밀번호를 외우게 하지 않고 얻을 수 있는 유일한 확인 수단이기도 하다.
 * 경우의 수가 적은 만큼 대입 방어({@link ShareAccessGuard})가 함께 있어야 의미를 갖는다.
 *
 * <p><b>여기서 나가는 것은 열람용이다.</b> 효력 있는 증명서는 지금까지처럼 병원에서
 * A4 로 인쇄한 것뿐이고(프론트 {@code Certificate.tsx}), 이 링크는 그 내용을 화면으로
 * 확인시켜 주는 용도다. 공개 페이지에 인쇄 버튼을 두지 않는 이유도 같다 —
 * 법정 서식이 통제 없이 재출력되게 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentShareService {

    private final CertificateRepository certificateRepository;
    private final VisitRepository visitRepository;
    private final KioskTokenGenerator tokenGenerator;
    private final PatientService patientService;
    private final PrescriptionService prescriptionService;
    private final ShareAccessGuard accessGuard;

    /** 링크 유효기간(일). 감열지에 마지막으로 출력한 시각부터 센다. */
    @Value("${document.share.ttl-days:7}")
    private int ttlDays;

    // ── 발급 ────────────────────────────────────────────────────────────────

    /**
     * 증명서 열람 토큰을 발급하거나, 이미 있으면 유효기간만 다시 시작한다.
     *
     * 토큰을 새로 돌리지 않는 이유: 재출력은 대개 "아까 준 종이를 잃어버렸다"가 아니라
     * "한 장 더 달라"다. 토큰을 갈아 끼우면 먼저 나간 종이의 QR 이 그 순간 죽는다.
     *
     * 출력 실패에 진료 흐름을 물리면 안 되므로, 호출하는 쪽(PrintService)은 이 값이
     * null 로 돌아와도 종이는 그대로 뽑는다 — QR 만 빠진다.
     */
    @Transactional
    public String issueCertificateToken(Long certificateId) {
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new NoSuchElementException("증명서를 찾을 수 없습니다: " + certificateId));

        if (certificate.getShareToken() == null || certificate.getShareToken().isBlank()) {
            certificate.setShareToken(tokenGenerator.generate());
        }
        certificate.setShareTokenIssuedAt(LocalDateTime.now());
        return certificate.getShareToken();
    }

    /** 진료요약서 열람 토큰. 규칙은 {@link #issueCertificateToken(Long)} 과 같다. */
    @Transactional
    public String issueVisitSummaryToken(Long visitId) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new NoSuchElementException("접수 정보를 찾을 수 없습니다: " + visitId));

        if (visit.getSummaryToken() == null || visit.getSummaryToken().isBlank()) {
            visit.setSummaryToken(tokenGenerator.generate());
        }
        visit.setSummaryTokenIssuedAt(LocalDateTime.now());
        return visit.getSummaryToken();
    }

    // ── 열람 ────────────────────────────────────────────────────────────────

    /**
     * 본인 확인 화면을 열기 전 점검. 링크가 실재하고 기간이 남았는지만 본다.
     *
     * 이 단계를 따로 두는 이유는 순전히 환자 경험 때문이다. 없는 링크·만료된 링크에도
     * 생년월일 입력창부터 보여주면, 환자는 자기 생년월일을 다 넣고 나서야 "이 링크는
     * 이미 닫혔다"는 말을 듣는다. 문서 내용은 여기서 한 글자도 나가지 않는다
     * ({@link SharedDocumentGateResponse} 주석 참고).
     */
    @Transactional(readOnly = true)
    public SharedDocumentGateResponse openCertificateGate(String token) {
        Certificate certificate = certificateRepository.findByShareToken(token)
                .orElseThrow(() -> new NoSuchElementException("링크가 올바르지 않습니다."));

        return new SharedDocumentGateResponse(requireNotExpired(certificate.getShareTokenIssuedAt()));
    }

    /**
     * 생년월일이 맞을 때에만 증명서 내용을 돌려준다.
     *
     * <p>비교 대상은 발급 당시 스냅샷({@code content.patientBirthDate})이다. 환자 정보가
     * 나중에 정정되더라도 환자 손에 있는 종이는 발급 시점의 사실이므로, 그 종이와 짝이 맞는
     * 값으로 맞춰야 한다.
     *
     * <p>스냅샷에 생년월일이 없으면 열어주지 않는다. 확인할 수단이 없는데 통과시키면
     * 생년월일을 등록하지 않은 환자의 서류만 아무나 열 수 있는 구멍이 된다. 만료 기산점이
     * 비어 있을 때 열어주지 않는 것과 같은 판단이다({@link #requireNotExpired}).
     *
     * <p>무효(VOID) 처리된 증명서는 확인만 통과하면 그대로 보여준다. 링크를 막아버리면 환자는
     * 자기 손의 종이가 왜 안 열리는지 알 수 없다. 무효 사유까지 함께 내려보내 화면에서
     * "무효"임을 분명히 알리는 편이 낫다 — {@code CertificateDocumentView} 가 이미 무효 배너를 그린다.
     */
    @Transactional(readOnly = true)
    public SharedCertificateResponse verifyCertificate(String token, String birthDateInput) {
        // 잠금 확인이 조회보다 먼저다. 잠긴 동안에는 맞는 값을 넣어도 열리지 않아야
        // 대입 시도가 실제로 느려진다.
        accessGuard.checkNotLocked(token);

        Certificate certificate = certificateRepository.findByShareToken(token)
                .orElseThrow(() -> new NoSuchElementException("링크가 올바르지 않습니다."));

        LocalDateTime expiresAt = requireNotExpired(certificate.getShareTokenIssuedAt());

        String expected = normalizeBirthDate(
                certificate.getContent() == null ? null : certificate.getContent().patientBirthDate());
        if (expected == null) {
            log.warn("증명서 열람: 스냅샷에 생년월일이 없어 본인 확인을 할 수 없다 (certificateId={})",
                    certificate.getId());
            throw new ShareVerificationFailedException(
                    "이 서류는 온라인으로 확인할 수 없습니다. 병원에 문의해 주세요.");
        }

        if (!expected.equals(normalizeBirthDate(birthDateInput))) {
            int remaining = accessGuard.recordFailure(token);   // 한도를 채우면 여기서 429 로 끊긴다
            throw new ShareVerificationFailedException(
                    "생년월일이 일치하지 않습니다. (남은 시도 %d회)".formatted(remaining));
        }

        accessGuard.reset(token);
        return SharedCertificateResponse.from(certificate, expiresAt);
    }

    /**
     * 생년월일에서 숫자만 남긴다. {@code 1990-01-01}, {@code 19900101}, {@code 1990.01.01} 이
     * 모두 같은 값이 되어, 환자가 어떤 형식으로 넣든 통과한다.
     *
     * 날짜로 파싱하지 않는 이유: 여기서 하려는 일은 두 값이 같은지 보는 것뿐이고, 파싱을 끼우면
     * 이상한 입력이 예외로 튀어 그 처리를 또 얹어야 한다. 8자리가 아닌 결과는 애초에 스냅샷
     * (항상 {@code yyyy-MM-dd})과 같아질 수 없으므로 그냥 틀린 값으로 떨어진다.
     */
    private String normalizeBirthDate(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    @Transactional(readOnly = true)
    public SharedVisitSummaryResponse readVisitSummary(String token) {
        Visit visit = visitRepository.findBySummaryToken(token)
                .orElseThrow(() -> new NoSuchElementException("링크가 올바르지 않습니다."));

        LocalDateTime expiresAt = requireNotExpired(visit.getSummaryTokenIssuedAt());
        String patientName = patientService.findById(visit.getPatientId()).name();

        // 처방이 사라졌더라도(재처방 도중, 접수 정정 등) 링크를 404 로 죽이지 않는다.
        // 환자 입장에서는 "언제 누구에게 진료받았다"만 확인돼도 종이의 목적은 달성된다.
        PrescriptionResponse prescription = null;
        try {
            prescription = prescriptionService.get(visit.getId());
        } catch (NoSuchElementException e) {
            log.warn("진료요약 열람: 처방이 없어 진료 정보만 표시한다 (visitId={})", visit.getId());
        }

        List<SharedVisitSummaryResponse.Disease> diseases = prescription == null ? List.of()
                : prescription.diseases().stream()
                        .map(d -> new SharedVisitSummaryResponse.Disease(d.kcdCode(), d.kcdNameKr()))
                        .toList();

        List<SharedVisitSummaryResponse.Medicine> medicines = prescription == null ? List.of()
                : prescription.details().stream()
                        .map(d -> new SharedVisitSummaryResponse.Medicine(
                                d.medicineName(), d.dosage(), d.durationDays()))
                        .toList();

        return new SharedVisitSummaryResponse(
                DisplayNo.visit(visit.getId()),
                patientName,
                DisplayNo.patient(visit.getPatientId()),
                visit.getVisitDate(),
                prescription == null ? null : prescription.memberName(),
                diseases,
                medicines,
                prescription == null ? null : prescription.aiComment(),
                expiresAt
        );
    }

    /**
     * 만료 검사. 통과하면 만료 예정 시각을 돌려준다 — 화면에 "언제까지 볼 수 있는지"를
     * 띄우기 위해서다.
     *
     * 기산점이 비어 있는 경우는 토큰만 있고 출력 기록이 없는 상태다. 정상 흐름에서는
     * 두 값이 항상 함께 채워지므로 데이터가 어긋난 것이고, 그때는 열어주지 않는다.
     */
    private LocalDateTime requireNotExpired(LocalDateTime issuedAt) {
        if (issuedAt == null) {
            throw new ShareLinkExpiredException("링크 유효기간 정보가 없습니다.");
        }
        LocalDateTime expiresAt = issuedAt.plusDays(ttlDays);
        if (LocalDateTime.now().isAfter(expiresAt)) {
            throw new ShareLinkExpiredException("링크 유효기간이 지났습니다.");
        }
        return expiresAt;
    }
}
