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
