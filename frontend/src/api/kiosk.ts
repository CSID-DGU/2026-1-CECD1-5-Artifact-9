import type { AnalysisType, HeatmapCrop } from "./analysis";
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
   * 어느 모델로 본 결과인가(방문자가 고른 촬영 대상). 이 필드가 생기기 전의 백엔드는 보내지 않으므로 선택 필드로
   * 두고, 없으면 "skin_lesion" 으로 본다 — 그때 키오스크는 피부 병변 모델만 썼다(백엔드 V13 의 DB 기본값도 같다).
   */
  analysisType?: AnalysisType;
  /** AI 신뢰도 등급. "low"면 caution 문구를 띄운다 — 자세한 배경은 api/analysis.ts 주석 참고. */
  confidenceLevel: "low" | "normal";
  /** 확신도가 낮을 때 띄울 문구. 경고할 것이 없으면 null. */
  caution: string | null;
  /**
   * 서버가 호출자에 맞는 경로를 내려준다 — 태블릿은 /api/kiosk/session/{token}/heatmap(무인증),
   * 의사 화면은 /api/v1/visits/{visitId}/preliminary/heatmap(JWT 필요). 직접 조립하지 말 것.
   */
  gradcamUrl: string | null;
  aiComment: string | null;
  analyzedAt: string;
  /** 히트맵을 그린 방식. 이 기록 전의 예비분석·구버전 백엔드는 null 이거나 없다. */
  heatmapStyle?: string | null;
  /**
   * 히트맵이 짚은 곳을 확대한 히트맵. gradcamUrl 처럼 서버가 호출자에 맞는 경로를 내려준다 —
   * 태블릿은 토큰 경로(무인증), 의사 화면은 접수 경로(JWT). 없으면 빈 배열이고 구버전 백엔드는 필드가 없다.
   */
  heatmapCrops?: HeatmapCrop[];
  /** 원본만으로는 신뢰도가 낮아 확대 영역까지 보고 다시 판정했을 때만: 원본 사진만 본 1순위. */
  fullImageTop1?: {
    diseaseCode: string;
    diseaseNameKo: string;
    confidence: number;
  } | null;
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

/**
 * 예비분석. 응답의 aiComment 는 늘 비어 있다 — 결과를 그린 뒤 requestKioskComment 로 따로 받는다.
 * 같은 접수의 분석이 이미 돌고 있으면 409(다른 기기에서 같은 QR 을 찍었거나, 응답이 늦어 다시 누른 경우).
 */
export function analyzeKioskSession(token: string, file: File, analysisType: AnalysisType) {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("analysisType", analysisType);

  return apiRequest<PreliminaryAnalysis>(`/api/kiosk/session/${encodeURIComponent(token)}/analyze`, {
    method: "POST",
    body: formData,
  });
}

/**
 * 직전 예비분석의 AI 참고 소견. LLM 이라 몇 초에서 수십 초가 걸려 분석 응답과 나눴다.
 * 생성에 실패해도 오류가 아니라 안내 문구가 온다. 이미 만들어 둔 소견이 있으면 다시 만들지 않고 그대로 준다.
 */
export function requestKioskComment(token: string) {
  return apiRequest<{ aiComment: string }>(`/api/kiosk/session/${encodeURIComponent(token)}/comment`, {
    method: "POST",
  });
}

/** 의사 진료 페이지 조회용. 예비분석이 없으면 404 — 호출부에서 catch(() => null)로 처리한다. */
export function getPreliminaryAnalysis(visitId: number) {
  return apiRequest<PreliminaryAnalysis>(`/api/v1/visits/${visitId}/preliminary`);
}
