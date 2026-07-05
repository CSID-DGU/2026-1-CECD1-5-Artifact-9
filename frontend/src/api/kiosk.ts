import { apiRequest } from "./client";

export type KioskPending = {
  visitId: number;
  patientName: string;
  receptionNumber: string;
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

/** 대기화면 폴링. 대기 환자가 없으면 404 — 호출부에서 catch(() => null)로 처리한다. */
export function getKioskPending() {
  return apiRequest<KioskPending>(`/api/kiosk/pending`);
}

export function analyzeKiosk(visitId: number, file: File) {
  const formData = new FormData();
  formData.append("visitId", String(visitId));
  formData.append("file", file);

  return apiRequest<PreliminaryAnalysis>(`/api/kiosk/analyze`, {
    method: "POST",
    body: formData,
  });
}

/** 의사 진료 페이지 조회용. 예비분석이 없으면 404 — 호출부에서 catch(() => null)로 처리한다. */
export function getPreliminaryAnalysis(visitId: number) {
  return apiRequest<PreliminaryAnalysis>(`/api/v1/visits/${visitId}/preliminary`);
}
