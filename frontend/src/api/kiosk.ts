import { apiRequest } from "./client";

export type KioskPending = {
  visitId: number;
  patientName: string;
  receptionNumber: string;
  kioskToken: string;
};

export type KioskSession = {
  visitId: number;
  patientName: string;
  receptionNumber: string;
  /** 이미 예비분석이 끝난 접수인지. true여도 재촬영/재분석은 가능하다. */
  analyzed: boolean;
};

export type PreliminaryTopK = {
  diseaseCode: string;
  diseaseNameKo: string;
  confidence: number;
};

export type PreliminaryAnalysis = {
  topK: PreliminaryTopK[];
  gradcamUrl: string | null;
  aiComment: string | null;
  analyzedAt: string;
};

/** QR 없이 자동 진입하는 폴백(/kiosk?auto=1)용. 대기 환자가 없으면 404 — 호출부에서 무시한다. */
export function getKioskPending() {
  return apiRequest<KioskPending>(`/api/kiosk/pending`);
}

/** QR 토큰으로 접수 정보 조회. 토큰이 유효하지 않으면 404. */
export function getKioskSession(token: string) {
  return apiRequest<KioskSession>(`/api/kiosk/session/${encodeURIComponent(token)}`);
}

export function analyzeKioskSession(token: string, file: File) {
  const formData = new FormData();
  formData.append("file", file);

  return apiRequest<PreliminaryAnalysis>(`/api/kiosk/session/${encodeURIComponent(token)}/analyze`, {
    method: "POST",
    body: formData,
  });
}

/** 의사 진료 페이지 조회용. 예비분석이 없으면 404 — 호출부에서 catch(() => null)로 처리한다. */
export function getPreliminaryAnalysis(visitId: number) {
  return apiRequest<PreliminaryAnalysis>(`/api/v1/visits/${visitId}/preliminary`);
}
