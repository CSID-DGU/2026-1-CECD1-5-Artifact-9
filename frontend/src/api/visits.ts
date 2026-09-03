import { apiRequest } from "./client";
import { kioskBaseHeader } from "./kioskHeader";

export type VisitStatus =
  | "RECEIVED"
  | "IN_PROGRESS"
  | "ANALYZING"
  | "ANALYZED"
  | "DIAGNOSED"
  | "PRESCRIBED"
  | "COMPLETED"
  | "CANCELLED";

export type Visit = {
  id: number;
  patientId: number;
  visitDate: string;
  status: VisitStatus;
  createdAt: string;
  receptionMemo?: string | null;
  /** 키오스크 QR 진입용 토큰. 컬럼 추가 이전 데이터는 null — issueKioskToken()으로 지연 발급한다. */
  kioskToken?: string | null;
};

export function getVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}`);
}

export function listVisits(status: VisitStatus) {
  return apiRequest<Visit[]>(`/api/v1/visits?status=${status}`);
}

export function listVisitsByPatient(patientId: number) {
  return apiRequest<Visit[]>(`/api/v1/visits?patientId=${patientId}`);
}

export function listVisitsByDate(date: string) {
  return apiRequest<Visit[]>(`/api/v1/visits?date=${encodeURIComponent(date)}`);
}

/**
 * 접수 생성. 백엔드가 곧바로 접수증(대기번호표)을 감열지로 뽑는다.
 *
 * @param kioskBaseUrl 접수 화면이 지금 쓰는 키오스크 주소. 이 값이 종이 QR이 된다 —
 *                     넘기지 않으면 화면 QR과 종이 QR이 다른 곳을 가리킬 수 있다.
 *                     자세한 내용은 api/kioskHeader.ts 참고.
 */
export function createVisit(
  patientId: number,
  receptionMemo?: string | null,
  kioskBaseUrl?: string
) {
  return apiRequest<Visit>(`/api/v1/visits`, {
    method: "POST",
    headers: kioskBaseHeader(kioskBaseUrl),
    body: JSON.stringify({ patientId, receptionMemo: receptionMemo ?? null }),
  });
}

/** 접수 취소. 접수 대기(RECEIVED) 상태의 건만 취소할 수 있다. */
export function cancelVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}`, {
    method: "DELETE",
  });
}

/** 키오스크 QR 토큰을 발급받는다(이미 있으면 그대로 반환). 접수 화면의 'QR 다시 보기'용. */
export function issueKioskToken(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/kiosk-token`, {
    method: "POST",
  });
}

export function startVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/start`, {
    method: "PATCH",
  });
}

export function diagnoseVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/diagnose`, {
    method: "PATCH",
  });
}

export function completeVisit(visitId: number) {
  return apiRequest<Visit>(`/api/v1/visits/${visitId}/complete`, {
    method: "PATCH",
  });
}
