package com.artifact.diagnosis.print;

import com.artifact.diagnosis.certificate.CertificateResponse;
import com.artifact.diagnosis.certificate.CertificateService;
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

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final PrintAgentClient printAgentClient;
    private final VisitService visitService;
    private final PatientService patientService;
    private final PrescriptionService prescriptionService;
    private final CertificateService certificateService;

    // ── 접수증 ──────────────────────────────────────────────────────────────

    /** 접수 직후 자동 출력. 결과를 기다리지 않는다. */
    public void printTicketAsync(VisitResponse visit) {
        PrintPayloads.Ticket payload = buildTicket(visit);
        if (payload == null) {
            return;
        }
        printAgentClient.sendAsync("/print/ticket", payload);
    }

    /** 접수 화면의 '티켓 인쇄' 버튼. 결과를 화면에 알려줘야 하므로 기다린다. */
    public PrintOutcome printTicket(Long visitId) {
        PrintPayloads.Ticket payload = buildTicket(visitService.findById(visitId));
        if (payload == null) {
            return PrintOutcome.failure("키오스크 토큰이 없어 티켓을 만들 수 없습니다.");
        }
        return printAgentClient.send("/print/ticket", payload);
    }

    private PrintPayloads.Ticket buildTicket(VisitResponse visit) {
        if (visit.kioskToken() == null || visit.kioskToken().isBlank()) {
            log.warn("키오스크 토큰이 없어 접수증을 출력하지 않는다 (visitId={})", visit.id());
            return null;
        }
        String patientName = patientService.findById(visit.patientId()).name();
        return new PrintPayloads.Ticket(
                visitNo(visit.id()),
                patientName,
                patientNo(visit.patientId()),
                visit.kioskToken()
        );
    }

    // ── 진료 요약서 ──────────────────────────────────────────────────────────

    /** 진료 완료 시 자동 출력. */
    public void printVisitSummaryAsync(Long visitId) {
        try {
            printAgentClient.sendAsync("/print/visit-summary", buildVisitSummary(visitId));
        } catch (RuntimeException e) {
            // 처방이 없는 등 조립 자체가 실패해도 진료 완료는 그대로 끝나야 한다.
            log.warn("진료요약서를 만들지 못했다 (visitId={}): {}", visitId, e.toString());
        }
    }

    /** 진료 화면의 '진료요약 인쇄' 버튼. */
    public PrintOutcome printVisitSummary(Long visitId) {
        return printAgentClient.send("/print/visit-summary", buildVisitSummary(visitId));
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
                patientNo(visit.patientId()),
                visit.visitDate() == null ? null : visit.visitDate().format(ISO),
                prescription.memberName(),
                diseases,
                medicines,
                // AI 코멘트는 의사가 확인/수정한 뒤 저장된 값이다. 종이에는 이 문구와 함께
                // '※ 의사 확인 완료' 가 반드시 같이 찍힌다 — print-agent 쪽에서 처리한다.
                prescription.aiComment()
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
        printAgentClient.sendAsync("/print/certificate-slip", buildSlip(certificate));
    }

    /** 증명서 화면의 '발급확인증 인쇄' 버튼. */
    public PrintOutcome printCertificateSlip(Long certificateId) {
        return printAgentClient.send("/print/certificate-slip",
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
                patientNo(c.patientId()),
                c.serialNo(),
                c.issuedAt() == null ? null : c.issuedAt().format(ISO),
                c.issuerName(),
                c.issuerLicense()
        );
    }

    // ── 표시용 번호 ──────────────────────────────────────────────────────────
    // 접수 화면(Reception.tsx)이 쓰는 것과 같은 규칙이다. 별도 컬럼이 아니라
    // ID 를 자리수 맞춰 찍는 방식이라, 종이와 화면의 번호가 항상 같도록 여기서도
    // 같은 규칙을 쓴다.

    private static String patientNo(Long patientId) {
        return "P" + String.format("%05d", patientId);
    }

    private static String visitNo(Long visitId) {
        return "V" + String.format("%05d", visitId);
    }
}
