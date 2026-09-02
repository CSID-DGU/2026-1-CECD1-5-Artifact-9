package com.artifact.diagnosis.docshare;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 감열지 QR 로 들어온 사람에게 보여줄 진료 요약.
 *
 * 담기는 항목은 감열지 진료요약서에 찍히는 것과 같다
 * ({@code PrintPayloads.VisitSummary} / print-agent 의 {@code build_visit_summary}).
 * 종이에 있는 내용을 화면에서 다시 보는 것이 이 링크의 목적이므로, 종이에 없는 값
 * (진단 신뢰도, 히트맵, 내부 ID)은 여기 넣지 않는다.
 *
 * 증명서와 달리 법정 서식이 없어 A4 스냅샷이 존재하지 않는다. 그래서 발급 시점의
 * 스냅샷이 아니라 지금 저장된 처방을 읽어 만든다 — 진료 후 처방이 정정되면 이
 * 화면도 정정된 내용을 보여준다. 보존이 필요한 서류가 아니라 안내용이기 때문이다.
 */
public record SharedVisitSummaryResponse(
        String visitNo,
        String patientName,
        String patientNo,
        LocalDateTime visitDateTime,
        String doctorName,
        List<Disease> diseases,
        List<Medicine> prescriptions,

        /** 의사가 확인·수정한 뒤 저장된 AI 참고 소견. 화면에도 '의사 확인 완료'를 같이 띄운다. */
        String aiSummary,

        /** 이 링크가 닫히는 시각. */
        LocalDateTime expiresAt
) {
    public record Disease(String code, String nameKo) {}

    public record Medicine(String drugName, String dosage, Integer durationDays) {}
}
