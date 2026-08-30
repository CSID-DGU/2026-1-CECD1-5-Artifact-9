import { apiRequest } from "./client";

export type AnalysisResponse = {
  analysisId: number;
  visitId: number;
  /** 실제로 모델에 들어간 이미지 ID. 2026-08-30 이전 분석은 기록이 없어 빈 배열이다. */
  analyzedImageIds: number[];
  modelVersion: string;
  top1: {
    diseaseCode: string;
    diseaseNameKo: string;
    confidence: number;
  };
  top5: Array<{
    rank: number;
    diseaseCode: string;
    diseaseNameKo: string;
    confidence: number;
    reason: string;
  }>;
  /**
   * AI 신뢰도 등급. "low"면 caution 문구를 화면에 띄운다.
   *
   * 예전에는 확신도가 낮으면 서버가 결과 대신 422를 줬다. 차단을 걷어내고 경고로 바꾼 이유는
   * 백엔드 LowConfidenceCaution 주석에 있다 — 차단이 하필 악성이 몰린 애매한 쪽부터 걷어냈다.
   */
  confidenceLevel: "low" | "normal";
  /** 확신도가 낮을 때 띄울 문구. 경고할 것이 없으면 null. 문구는 서버가 정한다(심각도별로 다름). */
  caution: string | null;
  inferenceTimeMs: number;
  analyzedAt: string;
  heatmapImageUrl: string | null;
};

export function requestAnalysis(visitId: number, imageIds: number[]) {
  return apiRequest<AnalysisResponse>(`/api/v1/visits/${visitId}/analysis`, {
    method: "POST",
    body: JSON.stringify({ imageIds }),
  });
}

export function getLatestAnalysis(visitId: number) {
  return apiRequest<AnalysisResponse>(`/api/v1/visits/${visitId}/analysis`);
}
