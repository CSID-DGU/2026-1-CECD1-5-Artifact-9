import type { AnalysisType, HeatmapCrop } from "../api/analysis";
import presetsJson from "./data/presets.json";

/**
 * 데모에서 업로드 대신 고르는 사진 40장(얼굴 20·피부 병변 20)과, 운영과 같은 모델로 미리 돌려 둔 결과.
 * 사진·히트맵은 public/demo/ 에 있다. 결과는 2026-11-19 운영 가중치(efficientnet_b0-…)로 만든 것이다.
 */
export type DemoPreset = {
  key: string;
  task: AnalysisType;
  no: string;
  /** 시연용으로 고른 사진의 원래 진단명(사진 아래 설명). */
  label: string;
  expectedCode: string;
  imageUrl: string;
  width: number;
  height: number;
  result: {
    confidenceLevel: "low" | "normal";
    top5: Array<{ rank: number; diseaseCode: string; diseaseNameKo: string; confidence: number }>;
    heatmapUrl: string;
    heatmapStyle: string | null;
    modelVersion: string;
    inferenceTimeMs: number;
    crops: HeatmapCrop[];
    fullImageTop1: { diseaseCode: string; diseaseNameKo: string; confidence: number } | null;
  };
};

export const DEMO_PRESETS = presetsJson as DemoPreset[];

/** 데모에서 고른 사진의 파일 이름 — 가짜 백엔드가 이 이름으로 어느 사진인지 안다(잘라 올려도 이름은 유지된다). */
export function presetFileName(preset: DemoPreset): string {
  return `demo-${preset.key}.jpg`;
}

export function presetFromFileName(name: string | null | undefined): DemoPreset | null {
  const m = /demo-((?:facial|lesion)-\d{2})/.exec(name ?? "");
  return m ? DEMO_PRESETS.find((p) => p.key === m[1]) ?? null : null;
}
