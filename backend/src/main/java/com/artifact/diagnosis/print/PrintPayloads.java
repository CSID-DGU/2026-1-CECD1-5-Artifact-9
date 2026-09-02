package com.artifact.diagnosis.print;

import java.util.List;

/**
 * print-agent 로 보낼 요청 본문들.
 *
 * 필드 이름은 print-agent/schemas.py 와 1:1로 맞춰져 있다. 이름이 어긋나면
 * 에이전트가 422 를 돌려주고, 그 실패는 로그에만 남고 본 흐름은 진행된다.
 */
public final class PrintPayloads {

    private PrintPayloads() {}

    /**
     * 접수증(대기번호표).
     *
     * {@code kioskBaseUrl} 은 접수 화면이 실제로 보고 있는 키오스크 주소다
     * ({@code X-Kiosk-Base-Url} 헤더 → {@link KioskBaseUrlPolicy} 검사를 통과한 값).
     * 화면에 뜬 QR 과 종이에 찍힌 QR 이 같은 곳을 가리키게 하려고 넘긴다.
     * null 이면 print-agent 가 자기 {@code KIOSK_BASE_URL} 기본값을 쓴다.
     */
    public record Ticket(
            String visitNo,
            String patientName,
            String patientNo,
            String kioskToken,
            String kioskBaseUrl
    ) {}

    /**
     * 진단 한 줄.
     *
     * {@code severityLevel} 은 보통 null 이다. 중증도는 AI 질병 테이블(disease)에만
     * 있는 값이고, 처방 상병(prescription_disease)은 kcd_disease_id 와 주상병 여부만
     * 들고 있어서 처방에서 꺼낼 중증도가 없다. 나중에 컬럼이 생기면 여기에 채우면 된다.
     */
    public record Disease(
            String code,
            String nameKo,
            String severityLevel
    ) {}

    public record Medicine(
            String drugName,
            String dosage,
            Integer durationDays
    ) {}

    /** 진료 요약서. */
    public record VisitSummary(
            Long visitId,
            String patientName,
            String patientNo,
            String visitDateTime,
            String doctorName,
            List<Disease> diseases,
            List<Medicine> prescriptions,
            String aiSummary
    ) {}

    /**
     * 증명서 발급 확인증.
     *
     * <b>법정 서식이 아니다.</b> 효력 있는 증명서는 기존 A4 인쇄 흐름
     * (프론트 Certificate.tsx 의 {@code window.print()})으로만 발급한다.
     * 감열지는 열·직사광선·가소제에 닿으면 수개월 안에 글자가 사라져 보존용
     * 서류로 쓸 수 없다. 이 출력물은 "발급되었다"는 사실을 환자에게 알리는
     * 안내용 보조 출력물이다.
     */
    public record CertificateSlip(
            Long certificateId,
            String typeLabel,
            String patientName,
            String patientNo,
            String serialNo,
            String issuedAt,
            String issuerName,
            String issuerLicenseNo
    ) {}
}
