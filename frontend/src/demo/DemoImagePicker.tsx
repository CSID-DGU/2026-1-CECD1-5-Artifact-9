import { useState } from "react";
import type { AnalysisType } from "../api/analysis";
import { DEMO_PRESETS, presetFileName, type DemoPreset } from "./presets";

const TABS: Array<{ task: AnalysisType; label: string }> = [
  { task: "facial", label: "얼굴 피부" },
  { task: "skin_lesion", label: "점·피부 병변" },
];

type Props = {
  /** 처음 보일 탭 — 키오스크는 체험자가 고른 촬영 대상, 진료실은 분석 종류 선택 상자 값. */
  initialTask: AnalysisType;
  /** 키오스크처럼 촬영 대상이 이미 정해졌으면 탭을 바꾸지 못하게 한다. */
  lockTask?: boolean;
  onClose: () => void;
  onPick: (file: File, preset: DemoPreset) => void;
};

/**
 * 데모에서 사진 업로드 대신 쓰는 선택 창. 미리 골라 둔 사진(얼굴 20·피부 병변 20) 가운데 하나를 누르면
 * 그 사진을 파일로 만들어 넘긴다 — 그 뒤는 실제 업로드와 같은 화면 흐름을 그대로 탄다.
 */
export default function DemoImagePicker({ initialTask, lockTask = false, onClose, onPick }: Props) {
  const [task, setTask] = useState<AnalysisType>(initialTask);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const presets = DEMO_PRESETS.filter((p) => p.task === task);

  const pick = async (preset: DemoPreset) => {
    if (loadingKey) return;
    setLoadingKey(preset.key);
    setError(null);
    try {
      const res = await fetch(preset.imageUrl);
      if (!res.ok) throw new Error(String(res.status));
      const blob = await res.blob();
      onPick(new File([blob], presetFileName(preset), { type: "image/jpeg" }), preset);
      onClose();
    } catch {
      setError("사진을 불러오지 못했습니다. 다시 눌러 주세요.");
    } finally {
      setLoadingKey(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      <div
        role="dialog"
        aria-label="데모 사진 선택"
        className="relative z-10 flex max-h-[86vh] w-full max-w-[760px] flex-col rounded-xl border border-gray-600 bg-card-bg shadow-2xl"
      >
        <div className="flex h-[40px] shrink-0 items-center justify-between rounded-t-xl border-b border-gray-700 bg-side-bg px-4">
          <h3 className="text-xs font-bold tracking-wide text-gray-200">사진 선택 (데모)</h3>
          <button onClick={onClose} aria-label="닫기" className="text-xl leading-none text-gray-400 transition-colors hover:text-white">
            ×
          </button>
        </div>

        <div className="shrink-0 border-b border-gray-700 px-4 py-3">
          <p className="text-[11px] leading-relaxed text-gray-300">
            데모에서는 파일 업로드·촬영 대신 미리 골라 둔 사진을 씁니다. 사진을 누르면 그 사진으로 이어서 진행합니다.
            분석 결과는 운영과 같은 AI 모델로 미리 돌려 둔 것입니다.
          </p>
          {!lockTask && (
            <div className="mt-2 flex gap-2">
              {TABS.map((t) => (
                <button
                  key={t.task}
                  type="button"
                  onClick={() => setTask(t.task)}
                  className={`rounded px-3 py-1 text-xs font-medium transition-colors ${
                    task === t.task ? "bg-blue-600 font-bold text-white" : "bg-gray-800 text-gray-400 hover:bg-gray-700"
                  }`}
                >
                  {t.label} ({DEMO_PRESETS.filter((p) => p.task === t.task).length})
                </button>
              ))}
            </div>
          )}
          {error && <p role="alert" className="mt-2 text-[11px] text-red-300">{error}</p>}
        </div>

        <div className="grid min-h-0 flex-1 grid-cols-3 gap-2 overflow-y-auto p-3 sm:grid-cols-4 md:grid-cols-5">
          {presets.map((preset) => (
            <button
              key={preset.key}
              type="button"
              onClick={() => void pick(preset)}
              disabled={loadingKey !== null}
              aria-label={`예시 ${preset.no} ${preset.label}`}
              className="group flex flex-col overflow-hidden rounded border border-gray-700 bg-side-bg text-left transition-colors hover:border-blue-400 disabled:cursor-wait disabled:opacity-60"
            >
              <span className="relative block aspect-square w-full overflow-hidden bg-gray-950">
                <img src={preset.imageUrl} alt="" loading="lazy" className="h-full w-full object-cover" />
                {loadingKey === preset.key && (
                  <span className="absolute inset-0 flex items-center justify-center bg-black/50 text-[11px] text-white">불러오는 중…</span>
                )}
              </span>
              <span className="px-1.5 py-1 text-[10px] leading-tight text-gray-300">
                <span className="font-mono text-blue-300">{preset.no}</span> {preset.label}
              </span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
