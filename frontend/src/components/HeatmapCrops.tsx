import { useEffect, useRef, useState } from "react";
import type { HeatmapCrop } from "../api/analysis";
import { AuthedImage } from "./AuthedImage";

/**
 * 히트맵 색띠. FastAPI 가 칠하는 jet 과 같은 색 순서다(fastapi/main.py _render_jet):
 * 값 0 진파랑 → 0.125 파랑 → 0.375 하늘 → 0.5 연두 → 0.625 노랑 → 0.875 빨강 → 1 진빨강.
 * 진료실·키오스크 모두 이 띠 하나로 읽는다 — 화면마다 색의 뜻이 다르면 같은 그림을 다르게 읽게 된다.
 */
const JET_GRADIENT =
  "linear-gradient(to right, #00007f 0%, #0000ff 12.5%, #00ffff 37.5%, #7fff7f 50%, #ffff00 62.5%, #ff0000 87.5%, #7f0000 100%)";

/**
 * 히트맵 색의 뜻. 확대 히트맵이 있으면 그 색 기준도 함께 적는다.
 *
 * heatmapStyle 을 넘기면 그 값으로 그린 방식을 가린다. 값이 없는(null) 기록은 2026-10 이전 방식이다 —
 * 노랑→빨강만 쓰고 바닥값 아래는 칠하지 않아 파랑이 없고, 노랑이 가장 약한 근거였다. 그 그림을 이 색띠로
 * 읽으면 노랑을 과대평가하고 "근거 아님"(파랑)을 찾지 못하므로 옛 범례를 그대로 보여 준다.
 * 넘기지 않으면(undefined) 지금 방식으로 본다 — 확대 히트맵 크게 보기처럼 지금 방식으로만 생기는 그림이다.
 */
export function HeatmapLegend({ hasCrops, heatmapStyle }: { hasCrops: boolean; heatmapStyle?: string | null }) {
  if (heatmapStyle !== undefined && !heatmapStyle?.startsWith("layercam-flip-jet")) {
    return (
      <p className="px-2 py-1.5 text-[10px] text-gray-400">
        🔴 빨간색 — AI가 진단 근거로 삼은 병변 부위 · 이전 방식으로 그린 히트맵
      </p>
    );
  }
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

/**
 * 로그인이 필요한 히트맵 이미지와 그 위의 확대 영역 상자. 상자는 이 이미지가 다 받아진 뒤에만 그린다 —
 * 받기 전에는 이미지 높이가 0 이라 비율 좌표의 상자가 납작한 띠가 되어 아래 범례 위에 겹친다.
 * "받아짐" 표시를 이 컴포넌트가 들고 있어서, 카드가 다시 뜨면 주소가 같아도 처음부터 다시 기다린다.
 * 부모가 들고 있으면 카드를 내리는 모든 곳에서 표시를 지워야 하고, 한 곳만 빠져도 상자가 먼저 그려진다.
 */
export function AuthedHeatmapWithBoxes({ src, alt, crops, activeRank }: {
  src: string; alt: string; crops: HeatmapCrop[]; activeRank: number | null;
}) {
  const [loadedSrc, setLoadedSrc] = useState<string | null>(null);
  return (
    <div className="relative">
      <AuthedImage src={src} alt={alt} className="w-full" style={{ display: "block" }} onLoad={() => setLoadedSrc(src)} />
      {loadedSrc === src && <CropBoxes crops={crops} activeRank={activeRank} />}
    </div>
  );
}

type CropGalleryProps = {
  crops: HeatmapCrop[];
  /** 재분석해도 확대 히트맵 경로가 같으므로 붙여서 브라우저 캐시를 피한다(전체 히트맵과 같은 방식). */
  cacheKey: string;
  activeRank: number | null;
  onActiveChange: (rank: number | null) => void;
  /**
   * 확대 영역을 모델에 다시 넣지 않고 전체 히트맵을 잘라 키운 태스크인가(안면부). 설명 문구만 바뀐다.
   * 서버가 crops[].source 를 주면 그쪽을 따른다 — 피부 병변도 재추론이 실패하면 잘라 키운 그림이 온다.
   */
  zoomOnly?: boolean;
  /**
   * 이미지에 로그인 토큰을 실어 받는가. 대기실 태블릿은 JWT 가 없고 서버가 키오스크 토큰 경로를 내려주므로
   * false 로 일반 <img> 를 쓴다 — 전체 히트맵과 같은 방식이다(KioskAnalyze). 태블릿 브라우저에 남은 직원
   * 토큰이 만료돼 있으면 AuthedImage 는 세션 만료로 처리해 버린다.
   */
  authed?: boolean;
};

/** 인증이 필요한 경로면 AuthedImage, 아니면 일반 <img>. */
function CropImage({ src, alt, className, authed, fallback }: {
  src: string; alt: string; className: string; authed: boolean; fallback?: React.ReactNode;
}) {
  return authed
    ? <AuthedImage src={src} alt={alt} className={className} fallback={fallback} />
    : <img src={src} alt={alt} className={className} />;
}

/**
 * 확대 히트맵 목록. 칸에 올리면 전체 이미지의 같은 번호 상자가 노랗게 바뀌고, 누르면 크게 본다.
 */
export function CropGallery({ crops, cacheKey, activeRank, onActiveChange, zoomOnly = false, authed = true }: CropGalleryProps) {
  const [expanded, setExpanded] = useState<HeatmapCrop | null>(null);
  const dialogRef = useRef<HTMLDialogElement | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  // 크게 보기는 네이티브 모달 <dialog> 다. showModal() 이 포커스를 안으로 옮기고 뒤 화면을 inert 로 만들어,
  // 키보드 Tab 이 가려진 뒤 화면(상병 후보 체크박스 등)으로 새지 않는다. Esc 도 브라우저가 처리한다.
  // 닫히면 연 칸으로 포커스를 돌려준다 — 키보드 사용자가 어디 있었는지 잃지 않게.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (expanded && !dialog.open) {
      openerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      dialog.showModal();
    } else if (!expanded && dialog.open) {
      dialog.close();
    }
  }, [expanded]);

  const close = () => dialogRef.current?.close();
  const handleClosed = () => {
    setExpanded(null);
    openerRef.current?.focus();
    openerRef.current = null;
  };

  const src = (crop: HeatmapCrop) => `${crop.imageUrl}?t=${encodeURIComponent(cacheKey)}`;
  // 한 분석의 확대 히트맵은 모두 같은 방식으로 그려진다. source 가 없는 옛 기록만 태스크로 짐작한다.
  const zoomed = crops[0]?.source ? crops[0].source === "zoom" : zoomOnly;

  return (
    <div className="flex flex-col gap-1">
      <p className="px-0.5 text-[11px] text-gray-400">
        확대 히트맵 {crops.length}곳
        <span className="ml-1 text-[10px] text-gray-500">
          {zoomed ? "· 전체 히트맵에서 잘라 키운 그림" : "· 확대 영역을 AI가 다시 본 근거"}
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
            <CropImage
              src={src(crop)}
              alt={`확대 히트맵 ${crop.rank}`}
              className="aspect-square w-full object-cover"
              authed={authed}
              fallback={<div className="aspect-square w-full animate-pulse bg-gray-900" />}
            />
            <span className="absolute left-0 top-0 bg-black/70 px-1 text-[10px] font-semibold text-white">{crop.rank}</span>
          </button>
        ))}
      </div>

      {/* 폭을 정해 둔다. 내용 크기에 맞추면 작은 확대 히트맵이 원래 크기 그대로 떠서 "크게 보기"가 아니게 되고,
          범례 색띠도 글자에 밀려 사라진다. 바깥(어두운 배경)을 누르면 닫힌다 — 그때 클릭 대상이 dialog 자신이다. */}
      <dialog
        ref={dialogRef}
        aria-label={expanded ? `확대 히트맵 ${expanded.rank}` : undefined}
        onClose={handleClosed}
        onClick={(e) => {
          if (e.target === e.currentTarget) close();
        }}
        className="m-auto w-[min(90vw,560px)] max-w-none overflow-visible border-0 bg-transparent p-0 backdrop:bg-black/80"
      >
        {expanded && (
          <div>
            <div className="mb-2 flex items-center justify-between text-sm text-gray-200">
              <span>확대 히트맵 {expanded.rank}</span>
              <button type="button" onClick={close} className="rounded bg-gray-700 px-2 py-0.5 text-xs text-gray-200 hover:bg-gray-600">
                닫기
              </button>
            </div>
            <CropImage src={src(expanded)} alt={`확대 히트맵 ${expanded.rank}`} className="max-h-[75vh] w-full rounded object-contain" authed={authed} />
            <HeatmapLegend hasCrops={false} />
          </div>
        )}
      </dialog>
    </div>
  );
}
