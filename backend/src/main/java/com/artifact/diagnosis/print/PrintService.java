package com.artifact.diagnosis.print;

import com.artifact.diagnosis.certificate.CertificateResponse;
import com.artifact.diagnosis.certificate.CertificateService;
import com.artifact.diagnosis.common.util.DisplayNo;
import com.artifact.diagnosis.docshare.DocumentShareService;
import com.artifact.diagnosis.patient.PatientService;
import com.artifact.diagnosis.prescription.PrescriptionResponse;
import com.artifact.diagnosis.prescription.PrescriptionService;
import com.artifact.diagnosis.visit.VisitResponse;
import com.artifact.diagnosis.visit.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 감열지 출력물을 조립해 print-agent 로 넘긴다.
 *
 * <b>이 클래스에는 {@code @Transactional} 을 붙이지 않는다.</b>
 * 여기서 하는 일은 (1) 짧은 DB 조회 몇 번, (2) 외부 HTTP 호출이다. 둘을 한
 * 트랜잭션으로 묶으면 프린터가 응답할 때까지 DB 커넥션을 붙들게 되고, 커넥션
 * 풀(10개)이 마르면 출력과 무관한 접수·조회 화면까지 함께 멈춘다.
 * AnalysisService / KioskService 가 트랜잭션 클래스를 따로 분리해 둔 것과 같은 이유다.
 *
 * 같은 이유로 호출도 서비스 안이 아니라 <b>컨트롤러에서 커밋이 끝난 뒤</b> 한다
 * (VisitService, CertificateService 는 클래스 단위 {@code @Transactional} 이다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrintService {

    /**
     * 접수 화면이 자기가 쓰는 키오스크 주소를 실어 보내는 헤더.
     *
     * 접수 담당자는 화면에서 키오스크 접속 주소를 바꿀 수 있다(Reception.tsx).
     * 그 값이 여기까지 와야 화면 QR 과 종이 QR 이 같은 곳을 가리킨다.
     * 헤더가 없으면(구버전 프론트, curl 테스트) print-agent 기본값이 쓰인다.
     */
    public static final String KIOSK_BASE_URL_HEADER = "X-Kiosk-Base-Url";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 프린터까지 실어 나르는 통로. 기본은 에이전트가 가지러 오는 {@link PrintJobQueue} 이고,
     * {@code print.mode=direct} 면 백엔드가 직접 부르는 {@link PrintAgentClient} 가 들어온다.
     * 이 클래스는 어느 쪽인지 알 필요가 없다 — 넘기는 경로와 본문은 양쪽이 같다.
     */
    private final PrintTransport printTransport;
    private final KioskBaseUrlPolicy kioskBaseUrlPolicy;
    private final VisitService visitService;
    private final PatientService patientService;
    private final PrescriptionService prescriptionService;
    private final CertificateService certificateService;
    private final DocumentShareService documentShareService;

    // ── 접수증 ──────────────────────────────────────────────────────────────

    /**
     * 접수 직후 자동 출력. 결과를 기다리지 않는다.
     *
     * @param kioskBaseUrl 접수 화면이 헤더로 보낸 키오스크 주소. null 이면 에이전트 기본값.
     */
    public void printTicketAsync(VisitResponse visit, String kioskBaseUrl) {
        PrintPayloads.Ticket payload = buildTicket(visit, kioskBaseUrl);
        if (payload == null) {
            return;
        }
        printTransport.sendAsync("/print/ticket", payload);
    }

    /** 접수 화면의 '티켓 인쇄' 버튼. 결과를 화면에 알려줘야 하므로 기다린다. */
    public PrintOutcome printTicket(Long visitId, String kioskBaseUrl) {
        PrintPayloads.Ticket payload = buildTicket(visitService.findById(visitId), kioskBaseUrl);
        if (payload == null) {
            return PrintOutcome.failure("키오스크 토큰이 없어 티켓을 만들 수 없습니다.");
        }
        return printTransport.send("/print/ticket", payload);
    }

    private PrintPayloads.Ticket buildTicket(VisitResponse visit, String kioskBaseUrl) {
        if (visit.kioskToken() == null || visit.kioskToken().isBlank()) {
            log.warn("키오스크 토큰이 없어 접수증을 출력하지 않는다 (visitId={})", visit.id());
            return null;
        }
        String patientName = patientService.findById(visit.patientId()).name();
        return new PrintPayloads.Ticket(
                DisplayNo.visit(visit.id()),
                patientName,
                DisplayNo.patient(visit.patientId()),
                visit.kioskToken(),
                // 검사에 걸리면 null 이 되고, 그러면 에이전트가 자기 기본값을 쓴다.
                // 접수를 실패시키지 않는다 — 종이가 기본 주소로 나가는 편이 낫다.
                kioskBaseUrlPolicy.sanitize(kioskBaseUrl)
        );
    }

    // ── 진료 요약서 ──────────────────────────────────────────────────────────

    /** 진료 완료 시 자동 출력. */
    public void printVisitSummaryAsync(Long visitId) {
        try {
            printTransport.sendAsync("/print/visit-summary", buildVisitSummary(visitId));
        } catch (RuntimeException e) {
            // 처방이 없는 등 조립 자체가 실패해도 진료 완료는 그대로 끝나야 한다.
            log.warn("진료요약서를 만들지 못했다 (visitId={}): {}", visitId, e.toString());
        }
    }

    /** 진료 화면의 '진료요약 인쇄' 버튼. */
    public PrintOutcome printVisitSummary(Long visitId) {
        return printTransport.send("/print/visit-summary", buildVisitSummary(visitId));
    }

    private PrintPayloads.VisitSummary buildVisitSummary(Long visitId) {
        VisitResponse visit = visitService.findById(visitId);
        PrescriptionResponse prescription = prescriptionService.get(visitId);
        String patientName = patientService.findById(visit.patientId()).name();

        List<PrintPayloads.Disease> diseases = prescription.diseases().stream()
                .map(d -> new PrintPayloads.Disease(d.kcdCode(), d.kcdNameKr(), null))
                .toList();

        List<PrintPayloads.Medicine> medicines = prescription.details().stream()
                .map(d -> new PrintPayloads.Medicine(d.medicineName(), d.dosage(), d.durationDays()))
                .toList();

        return new PrintPayloads.VisitSummary(
                visitId,
                patientName,
                DisplayNo.patient(visit.patientId()),
                visit.visitDate() == null ? null : visit.visitDate().format(ISO),
                prescription.memberName(),
                diseases,
                medicines,
                // AI 코멘트는 의사가 확인/수정한 뒤 저장된 값이다. 종이에는 이 문구와 함께
                // '※ 의사 확인 완료' 가 반드시 같이 찍힌다 — print-agent 쪽에서 처리한다.
                prescription.aiComment(),
                shareToken(() -> documentShareService.issueVisitSummaryToken(visitId), "진료요약", visitId)
        );
    }

    // ── 증명서 발급 확인증 ────────────────────────────────────────────────────

    /**
     * 증명서 발급 직후 자동 출력.
     *
     * 여기서 나가는 종이는 법정 서식이 아니다. 효력 있는 증명서는 기존 A4 인쇄
     * 흐름(프론트 {@code Certificate.tsx} 의 {@code window.print()})으로만 발급하며,
     * 그 흐름은 이 기능과 무관하게 그대로다. 감열지는 열·직사광선·가소제에 닿으면
     * 수개월 안에 글자가 사라져 보존용 서류로 쓸 수 없다.
     */
    public void printCertificateSlipAsync(CertificateResponse certificate) {
        printTransport.sendAsync("/print/certificate-slip", buildSlip(certificate));
    }

    /** 증명서 화면의 '발급확인증 인쇄' 버튼. */
    public PrintOutcome printCertificateSlip(Long certificateId) {
        return printTransport.send("/print/certificate-slip",
                buildSlip(certificateService.get(certificateId)));
    }

    private PrintPayloads.CertificateSlip buildSlip(CertificateResponse c) {
        String patientName = c.content() != null && c.content().patientName() != null
                ? c.content().patientName()
                : patientService.findById(c.patientId()).name();

        return new PrintPayloads.CertificateSlip(
                c.id(),
                c.typeLabel(),
                patientName,
                DisplayNo.patient(c.patientId()),
                c.serialNo(),
                c.issuedAt() == null ? null : c.issuedAt().format(ISO),
                c.issuerName(),
                c.issuerLicense(),
                shareToken(() -> documentShareService.issueCertificateToken(c.id()), "증명서", c.id())
        );
    }

    // ── QR 열람 토큰 ─────────────────────────────────────────────────────────

    /**
     * 열람 토큰 발급을 감싼다. 실패해도 출력은 그대로 진행한다.
     *
     * 토큰은 QR 하나를 위한 부가 정보다. 발급이 실패했다고 종이를 안 뽑아 버리면,
     * 정작 필요한 발급번호·환자명·발급일자까지 환자 손에 못 들어간다.
     * 그 경우 에이전트는 토큰이 null 인 것을 보고 QR 만 빼고 인쇄한다.
     */
    private String shareToken(java.util.function.Supplier<String> issue, String kind, Long id) {
        try {
            return issue.get();
        } catch (RuntimeException e) {
            log.warn("{} 열람 토큰을 발급하지 못했다 — QR 없이 출력한다 (id={}): {}", kind, id, e.toString());
            return null;
        }
    }

}
