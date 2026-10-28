import { useEffect, useState } from "react";
import type { HeatmapCrop } from "../api/analysis";
import { AuthedImage } from "./AuthedImage";

/**
 * 히트맵 색띠. FastAPI 가 칠하는 jet 과 같은 색 순서다(fastapi/main.py _render_jet):
 * 값 0 진파랑 → 0.125 파랑 → 0.375 하늘 → 0.5 연두 → 0.625 노랑 → 0.875 빨강 → 1 진빨강.
 * 진료실·키오스크 모두 이 띠 하나로 읽는다 — 화면마다 색의 뜻이 다르면 같은 그림을 다르게 읽게 된다.
 */
const JET_GRADIENT =
  "linear-gradient(to right, #00007f 0%, #0000ff 12.5%, #00ffff 37.5%, #7fff7f 50%, #ffff00 62.5%, #ff0000 87.5%, #7f0000 100%)";

/** 히트맵 색의 뜻. 확대 히트맵이 있으면 그 색 기준도 함께 적는다. */
export function HeatmapLegend({ hasCrops }: { hasCrops: boolean }) {
  return (
    <div className="px-2 py-1.5 text-[10px] text-gray-400">
      <div className="flex items-center gap-1.5">
        <span className="shrink-0">근거로 삼지 않은 곳</span>
        <span className="h-1.5 flex-1 rounded-sm" style={{ background: JET_GRADIENT }} />
        <span className="shrink-0 text-red-300">AI가 진단 근거로 삼은 곳</span>
      </div>
      {hasCrops && (
        <p className="mt-1 leading-snug">
          흰 상자 번호는 아래 확대 히트맵과 짝입니다. 확대 히트맵은 그 영역 안에서 다시 칠해, 색의 기준이 영역마다 다릅니다.
        </p>
      )}
    </div>
  );
}

/** 유효한 [x0, y0, x1, y1] (각 0~1) 인가. 구버전·손상 데이터에서 상자가 화면 밖으로 튀지 않게 거른다. */
function isValidBox(box: number[] | null | undefined): box is [number, number, number, number] {
  return (
    Array.isArray(box) && box.length === 4 && box.every((v) => Number.isFinite(v) && v >= 0 && v <= 1)
    && box[0] < box[2] && box[1] < box[3]
  );
}

/**
 * 전체 이미지 위에 확대 영역 상자를 그린다.
 * 부모가 `position: relative` 이고 이미지와 **같은 크기**여야 한다 — 좌표가 이미지에 대한 비율이라서다.
 * 상자는 클릭을 가로채지 않는다(pointer-events-none). 이미지 위 버튼·토글이 그대로 동작해야 한다.
 */
export function CropBoxes({ crops, activeRank }: { crops: HeatmapCrop[]; activeRank: number | null }) {
  return (
    <>
      {crops.filter((c) => isValidBox(c.boxNorm)).map((c) => {
        const [x0, y0, x1, y1] = c.boxNorm;
        const active = c.rank === activeRank;
        return (
          <div
            key={c.rank}
            className={`pointer-events-none absolute border-2 ${active ? "border-yellow-300" : "border-white/90"}`}
            style={{
              left: `${x0 * 100}%`,
              top: `${y0 * 100}%`,
              width: `${(x1 - x0) * 100}%`,
              height: `${(y1 - y0) * 100}%`,
              boxShadow: "0 0 0 1px rgba(0,0,0,0.6)",
            }}
          >
            <span className={`absolute left-0 top-0 px-1 text-[10px] font-semibold ${active ? "bg-yellow-300 text-black" : "bg-black/70 text-white"}`}>
              {c.rank}
            </span>
          </div>
        );
      })}
    </>
  );
}

type CropGalleryProps = {
  crops: HeatmapCrop[];
  /** 재분석해도 확대 히트맵 경로가 같으므로 붙여서 브라우저 캐시를 피한다(전체 히트맵과 같은 방식). */
  cacheKey: string;
  activeRank: number | null;
  onActiveChange: (rank: number | null) => void;
  /** 확대 영역을 모델에 다시 넣지 않고 전체 히트맵을 잘라 키운 태스크인가(안면부). 설명 문구만 바뀐다. */
  zoomOnly?: boolean;
};

/**
 * 확대 히트맵 목록. 칸에 올리면 전체 이미지의 같은 번호 상자가 노랗게 바뀌고, 누르면 크게 본다.
 */
export function CropGallery({ crops, cacheKey, activeRank, onActiveChange, zoomOnly = false }: CropGalleryProps) {
  const [expanded, setExpanded] = useState<HeatmapCrop | null>(null);

  // 크게 보기는 Esc 로도 닫힌다. 키보드만 쓰는 사용자가 갇히지 않게 한다.
  useEffect(() => {
    if (!expanded) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setExpanded(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [expanded]);

  const src = (crop: HeatmapCrop) => `${crop.imageUrl}?t=${encodeURIComponent(cacheKey)}`;

  return (
    <div className="flex flex-col gap-1">
      <p className="px-0.5 text-[11px] text-gray-400">
        확대 히트맵 {crops.length}곳
        <span className="ml-1 text-[10px] text-gray-500">
          {zoomOnly ? "· 전체 히트맵에서 잘라 키운 그림" : "· 확대 영역을 AI가 다시 본 근거"}
        </span>
      </p>
      <div className="grid grid-cols-3 gap-1.5">
        {crops.map((crop) => (
          <button
            key={crop.rank}
            type="button"
            onClick={() => setExpanded(crop)}
            onMouseEnter={() => onActiveChange(crop.rank)}
            onMouseLeave={() => onActiveChange(null)}
            onFocus={() => onActiveChange(crop.rank)}
            onBlur={() => onActiveChange(null)}
            className={`relative overflow-hidden rounded border bg-gray-950 ${crop.rank === activeRank ? "border-yellow-300" : "border-gray-700"}`}
            aria-label={`확대 히트맵 ${crop.rank} 크게 보기`}
          >
            <AuthedImage
              src={src(crop)}
              alt={`확대 히트맵 ${crop.rank}`}
              className="aspect-square w-full object-cover"
              fallback={<div className="aspect-square w-full animate-pulse bg-gray-900" />}
            />
            <span className="absolute left-0 top-0 bg-black/70 px-1 text-[10px] font-semibold text-white">{crop.rank}</span>
          </button>
        ))}
      </div>

      {expanded && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`확대 히트맵 ${expanded.rank}`}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-6"
          onClick={() => setExpanded(null)}
        >
          {/* 폭을 정해 둔다. 내용 크기에 맞추면 작은 확대 히트맵이 원래 크기 그대로 떠서 "크게 보기"가 아니게 되고,
              범례 색띠도 글자에 밀려 사라진다. */}
          <div className="w-[min(90vw,560px)]" onClick={(e) => e.stopPropagation()}>
            <div className="mb-2 flex items-center justify-between text-sm text-gray-200">
              <span>확대 히트맵 {expanded.rank}</span>
              <button type="button" onClick={() => setExpanded(null)} className="rounded bg-gray-700 px-2 py-0.5 text-xs hover:bg-gray-600">
                닫기
              </button>
            </div>
            <AuthedImage src={src(expanded)} alt={`확대 히트맵 ${expanded.rank}`} className="max-h-[75vh] w-full rounded object-contain" />
            <HeatmapLegend hasCrops={false} />
          </div>
        </div>
      )}
    </div>
  );
}
