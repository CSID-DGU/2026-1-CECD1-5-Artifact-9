import { apiRequest } from "./client";

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

export function createVisit(patientId: number, receptionMemo?: string | null) {
  return apiRequest<Visit>(`/api/v1/visits`, {
    method: "POST",
    body: JSON.stringify({ patientId, receptionMemo: receptionMemo ?? null }),
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
