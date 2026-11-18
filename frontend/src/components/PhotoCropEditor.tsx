import { useRef } from "react";
import { clampRect, type NormRect } from "../utils/printedPhotoCrop";

type Edge = { l?: boolean; t?: boolean; r?: boolean; b?: boolean };
type DragMode = "move" | Edge;

const MIN_SIDE = 0.08; // 상자의 최소 폭·높이(사진 대비)

const HANDLES: { key: string; edge: Edge; style: React.CSSProperties; cursor: string; label: string }[] = [
  { key: "tl", edge: { l: true, t: true }, style: { left: 0, top: 0 }, cursor: "nwse-resize", label: "왼쪽 위 모서리" },
  { key: "tr", edge: { r: true, t: true }, style: { left: "100%", top: 0 }, cursor: "nesw-resize", label: "오른쪽 위 모서리" },
  { key: "bl", edge: { l: true, b: true }, style: { left: 0, top: "100%" }, cursor: "nesw-resize", label: "왼쪽 아래 모서리" },
  { key: "br", edge: { r: true, b: true }, style: { left: "100%", top: "100%" }, cursor: "nwse-resize", label: "오른쪽 아래 모서리" },
  { key: "t", edge: { t: true }, style: { left: "50%", top: 0 }, cursor: "ns-resize", label: "위 변" },
  { key: "b", edge: { b: true }, style: { left: "50%", top: "100%" }, cursor: "ns-resize", label: "아래 변" },
  { key: "l", edge: { l: true }, style: { left: 0, top: "50%" }, cursor: "ew-resize", label: "왼쪽 변" },
  { key: "r", edge: { r: true }, style: { left: "100%", top: "50%" }, cursor: "ew-resize", label: "오른쪽 변" },
];

/** 끈 거리(dx, dy: 사진 대비 비율)만큼 상자를 옮기거나 변을 민다. */
function dragRect(start: NormRect, mode: DragMode, dx: number, dy: number): NormRect {
  if (mode === "move") {
    return clampRect({ ...start, x: start.x + dx, y: start.y + dy });
  }
  let x0 = start.x;
  let y0 = start.y;
  let x1 = start.x + start.w;
  let y1 = start.y + start.h;
  if (mode.l) x0 = Math.min(Math.max(0, x0 + dx), x1 - MIN_SIDE);
  if (mode.r) x1 = Math.max(Math.min(1, x1 + dx), x0 + MIN_SIDE);
  if (mode.t) y0 = Math.min(Math.max(0, y0 + dy), y1 - MIN_SIDE);
  if (mode.b) y1 = Math.max(Math.min(1, y1 + dy), y0 + MIN_SIDE);
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
}

type Props = {
  src: string;
  /** 사진 폭 ÷ 높이(EXIF 방향 반영) — 화면 상자를 사진 비율 그대로 만들어 좌표가 1:1 로 맞게 한다. */
  aspect: number;
  rect: NormRect;
  onChange: (rect: NormRect) => void;
  disabled?: boolean;
};

/**
 * 분석할 부분을 고르는 상자. 상자 안을 끌면 옮기고, 모서리·변의 점을 끌면 크기를 바꾼다(손가락·마우스 모두).
 * 상자 밖은 어둡게 덮어 무엇이 분석에 들어가는지 보이게 한다.
 */
export function PhotoCropEditor({ src, aspect, rect, onChange, disabled = false }: Props) {
  const boxRef = useRef<HTMLDivElement | null>(null);
  const drag = useRef<{ mode: DragMode; pointerId: number; x: number; y: number; start: NormRect } | null>(null);

  const begin = (mode: DragMode) => (e: React.PointerEvent<HTMLElement>) => {
    if (disabled) return;
    e.preventDefault();
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { mode, pointerId: e.pointerId, x: e.clientX, y: e.clientY, start: rect };
  };

  const move = (e: React.PointerEvent<HTMLElement>) => {
    const d = drag.current;
    const box = boxRef.current;
    if (!d || d.pointerId !== e.pointerId || !box) return;
    const bounds = box.getBoundingClientRect();
    if (bounds.width === 0 || bounds.height === 0) return;
    onChange(dragRect(d.start, d.mode, (e.clientX - d.x) / bounds.width, (e.clientY - d.y) / bounds.height));
  };

  const end = (e: React.PointerEvent<HTMLElement>) => {
    if (drag.current?.pointerId === e.pointerId) drag.current = null;
  };

  const pct = (v: number) => `${(v * 100).toFixed(3)}%`;

  return (
    <div
      ref={boxRef}
      // touch-none: 상자를 끄는 동안 화면이 스크롤되지 않게 한다.
      className="relative mx-auto select-none overflow-hidden rounded bg-gray-950 touch-none"
      style={{ aspectRatio: String(aspect), width: `min(100%, calc(56vh * ${aspect}))` }}
      onPointerMove={move}
      onPointerUp={end}
      onPointerCancel={end}
    >
      <img src={src} alt="분석할 사진" draggable={false} className="pointer-events-none absolute inset-0 h-full w-full" />
      <div
        role="group"
        aria-label="분석할 부분"
        data-testid="crop-box"
        className={`absolute border-2 border-sky-300 ${disabled ? "" : "cursor-move"}`}
        style={{
          left: pct(rect.x),
          top: pct(rect.y),
          width: pct(rect.w),
          height: pct(rect.h),
          // 상자 밖을 어둡게 — 바깥 그림자를 크게 펼쳐 덮는다(부모가 overflow-hidden 이라 사진 밖으로 안 나간다).
          boxShadow: "0 0 0 9999px rgba(3, 7, 18, 0.62)",
        }}
        onPointerDown={begin("move")}
      >
        {!disabled && HANDLES.map((h) => (
          <span
            key={h.key}
            role="presentation"
            aria-label={h.label}
            data-handle={h.key}
            onPointerDown={begin(h.edge)}
            // 손가락으로 잡기 쉽게 32px 판을 두고 가운데에 작은 점을 그린다.
            className="absolute flex h-8 w-8 -translate-x-1/2 -translate-y-1/2 items-center justify-center"
            style={{ ...h.style, cursor: h.cursor }}
          >
            <span className="h-3.5 w-3.5 rounded-sm border-2 border-sky-300 bg-white shadow" />
          </span>
        ))}
      </div>
    </div>
  );
}
