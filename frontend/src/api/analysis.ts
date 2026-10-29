import { apiRequest } from "./client";

/**
 * 분석 종류(모델) 코드. 백엔드 enum AnalysisType 의 code() 와 FastAPI TASKS 의 키와 같은 소문자 문자열이다
 * (계약 원본: docs/aihub-datasets-plan.md §3-1). 새 태스크는 세 층에 같이 추가해야 하므로 여기서만 늘리지 않는다.
 */
export type AnalysisType = "skin_lesion" | "facial";

/** 화면용 라벨. "AI 분석" 버튼 옆 선택 상자 폭이 좁아 짧게 둔다. */
export const ANALYSIS_TYPE_LABELS: Record<AnalysisType, string> = {
  skin_lesion: "피부 병변",
  facial: "안면부 피부질환",
};

export type AnalysisResponse = {
  analysisId: number;
  visitId: number;
  /** 실제로 모델에 들어간 이미지 ID. 2026-08-30 이전 분석은 기록이 없어 빈 배열이다. */
  analyzedImageIds: number[];
  modelVersion: string;
  /**
   * 어느 모델의 결과인가. 이 필드가 생기기 전의 백엔드는 응답에 넣지 않으므로 선택 필드로 두고,
   * 없으면 화면에서 "skin_lesion" 으로 간주한다 — 그때는 모델이 하나뿐이었다(DB 쪽 기본값도 같다, V10).
   */
  analysisType?: AnalysisType;
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
  /** 히트맵을 그린 방식(FastAPI HEATMAP_STYLE). 이 기록 전의 분석·구버전 백엔드는 null 이거나 없다. */
  heatmapStyle?: string | null;
  /** 히트맵이 짚은 곳을 확대한 히트맵. 없으면 빈 배열이고, 구버전 백엔드는 필드 자체가 없다. */
  heatmapCrops?: HeatmapCrop[];
  /**
   * 원본만으로는 신뢰도가 낮아 확대 영역까지 함께 보고 다시 판정했을 때만 온다 — 원본 사진만 본 1순위.
   * 이때 top1·top5 는 다시 판정한 결과이고, confidenceLevel 은 원본 기준 그대로("low")다.
   */
  fullImageTop1?: {
    diseaseCode: string;
    diseaseNameKo: string;
    confidence: number;
  } | null;
};

/** 확대 히트맵 하나. boxNorm 은 원본 이미지 기준 [x0, y0, x1, y1] 이고 각 0~1 이다. */
export type HeatmapCrop = {
  rank: number;
  /** 인증이 필요한 이미지 경로. 재분석해도 같으므로 화면은 analyzedAt 을 붙여 캐시를 피한다. */
  imageUrl: string;
  boxNorm: number[];
  /** 그 영역의 히트맵 최댓값(0~1). */
  peak: number | null;
  /** reinfer: 확대 영역을 AI에 다시 넣어 그림, zoom: 전체 히트맵을 잘라 키움. 이 필드 전의 기록은 null. */
  source?: "reinfer" | "zoom" | null;
};

/**
 * analysisType 을 생략하면 skin_lesion — 백엔드도 필드가 없으면 같은 기본값을 쓰므로(AnalysisRequest)
 * 기존 호출부는 바꾸지 않아도 전과 같다. 본문 키 이름은 백엔드 AnalysisRequest 의 필드명과 같아야 한다.
 */
export function requestAnalysis(visitId: number, imageIds: number[], analysisType: AnalysisType = "skin_lesion") {
  return apiRequest<AnalysisResponse>(`/api/v1/visits/${visitId}/analysis`, {
    method: "POST",
    body: JSON.stringify({ imageIds, analysisType }),
  });
}

export function getLatestAnalysis(visitId: number) {
  return apiRequest<AnalysisResponse>(`/api/v1/visits/${visitId}/analysis`);
}
