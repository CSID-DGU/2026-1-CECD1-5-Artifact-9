import { apiRequest } from "./client";

/**
 * 이 응답만 인증 없이 나가므로 토큰 외에는 아무것도 담기지 않는다.
 * 환자 이름·접수번호는 /kiosk/{token} 으로 이동한 뒤 getKioskSession 에서 받는다.
 */
export type KioskPending = {
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
  /**
   * 서버가 호출자에 맞는 경로를 내려준다 — 태블릿은 /api/kiosk/session/{token}/heatmap(무인증),
   * 의사 화면은 /api/v1/visits/{visitId}/preliminary/heatmap(JWT 필요). 직접 조립하지 말 것.
   */
  gradcamUrl: string | null;
  aiComment: string | null;
  analyzedAt: string;
};

/**
 * QR 없이 자동 진입하는 폴백(/kiosk?auto=1)용.
 * 서버에서 기본 비활성이라(KIOSK_AUTO_PENDING) 대기 환자가 없을 때와 똑같이 404가 온다 — 호출부에서 무시한다.
 */
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
