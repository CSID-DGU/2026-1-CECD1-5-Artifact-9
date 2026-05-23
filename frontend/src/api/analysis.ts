import { apiRequest } from "./client";

export type AnalysisResponse = {
  analysisId: number;
  visitId: number;
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
  }>;
  inferenceTimeMs: number;
  analyzedAt: string;
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
