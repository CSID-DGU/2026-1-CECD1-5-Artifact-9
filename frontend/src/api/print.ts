import { apiRequest } from "./client";
import { kioskBaseHeader } from "./kioskHeader";

/**
 * 감열지(영수증) 프린터 수동 재출력.
 *
 * 접수·진료완료·증명서 발급 시에는 백엔드가 알아서 뽑아준다. 여기 있는 함수들은
 * 용지가 걸렸거나 환자가 종이를 잃어버렸을 때 화면에서 다시 뽑기 위한 것이다.
 *
 * 왜 백엔드를 거치는가: 프린터는 접수 데스크 맥북에 물려 있고 print-agent 는 그
 * 맥북에서 평문 HTTP(localhost:5051)로 돈다. 배포된 프론트는 HTTPS 라서 브라우저가
 * mixed content 로 차단하기 때문에 직접 부를 수 없다.
 */
export type PrintOutcome = {
  /** false 여도 HTTP 는 200 이다. 프린터가 꺼진 것은 서버 오류가 아니라 안내할 상황이다. */
  ok: boolean;
  detail: string;
};

/**
 * 접수증(대기번호표) — 키오스크 QR 포함.
 *
 * @param kioskBaseUrl 접수 시 자동 출력된 종이와 같은 주소가 찍혀야 한다.
 *                     재출력한 종이만 다른 곳을 가리키면 그게 더 나쁘다.
 */
export function printTicket(visitId: number, kioskBaseUrl?: string) {
  return apiRequest<PrintOutcome>(`/api/v1/visits/${visitId}/print/ticket`, {
    method: "POST",
    headers: kioskBaseHeader(kioskBaseUrl),
  });
}

/** 진료 요약서 — 진단·처방·AI 코멘트. 처방이 저장돼 있어야 한다. */
export function printVisitSummary(visitId: number) {
  return apiRequest<PrintOutcome>(`/api/v1/visits/${visitId}/print/visit-summary`, { method: "POST" });
}

/**
 * 증명서 발급 확인증.
 *
 * 법정 서식이 아니다 — 효력 있는 증명서는 증명서 화면의 '인쇄'(A4)로만 발급한다.
 * 감열지는 열·빛에 노출되면 수개월 안에 글자가 사라져 보존용으로 쓸 수 없다.
 */
export function printCertificateSlip(certificateId: number) {
  return apiRequest<PrintOutcome>(`/api/v1/certificates/${certificateId}/print/slip`, { method: "POST" });
}
