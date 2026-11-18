/**
 * 종이에 인쇄된 사진을 휴대폰으로 찍었을 때, 그 안의 "인쇄된 사진" 부분만 찾는다.
 *
 * 부스에서는 인쇄물을 찍는다. 찍은 그대로 분석하면 흰 여백·제목 글자·책상까지 모델 입력이 된다.
 * 모델은 사진을 비율과 상관없이 224×224 로 늘려 넣으므로, 이것들이 병변과 같은 무게로 섞인다.
 *
 * 측정(2026-11-18, 부스 이미지 40장을 흰 A4 에 제목·테두리·캡션과 함께 인쇄해 책상 위에서 찍은 것처럼 만든 120장 —
 * 종이 전체 / 근접 / 사진이 화면을 거의 채우는 컷, 기울기 ±7°·원근·조명·반사·흐림·JPEG, 운영과 같은 모델과 확대 재판정):
 *   - 찍은 그대로: 병변 49/60, 얼굴 44/60. 기저세포암을 종이째 찍으면 '염증성 피부질환'으로 나온다(부스에서 겪은 것).
 *   - 이 함수로 자른 것: 병변 60/60, 얼굴 60/60. 115/120 장에서 찾았고(정답 사각형과 IoU 평균 0.91, 모두 0.8 이상),
 *     못 찾은 5장은 사진이 화면을 거의 채운 컷이라 전체를 그대로 써도 맞았다.
 *   - 인쇄물이 아닌 사진 100장(부스 원본 이미지 40, 실제 피부 사진 60)에서는 한 장도 자르지 않았다.
 *
 * 방법(축소한 사진 한 장에서 수십 ms):
 *   1. 종이 색을 사진에서 잰다 — 밝은 쪽 픽셀의 색(조명 색이 묻어 있다)과 밝기.
 *   2. 그 색·밝기에 가깝고 매끈한 곳 = 종이. 나머지 = 내용(사진·글자·책상·그림자).
 *      종이라고 본 곳이 거칠면(옅은 피부의 결) 인쇄물이 아니라고 보고 멈춘다.
 *   3. 내용에 둘러싸인 작은 종이색 구멍을 메운다 — 사진 안의 옅은 피부, 인쇄 테두리와 사진 사이의 틈.
 *   4. 내용을 얇게 깎았다 되살려(열림 연산) 글자·테두리 선 같은 가는 것을 지운다.
 *   5. 이어진 덩어리 가운데 직사각형인 가장 큰 것을 고른다. ±15° 돌려 가며 감싸는 가장 작은 직사각형을 얼마나
 *      채우는지 본다 — 인쇄된 사진은 기울어 찍혀도 직사각형이고, 병변(타원·불규칙)은 덜 찬다. 이 검사가 없으면
 *      인쇄물이 아닌 병변 사진에서 옅은 피부를 종이로 보고 병변만 오려낸다. 책상·종이 밖 배경은 사진 가장자리
 *      여러 곳에 닿아 걸러진다. 가까이 찍어 사진이 좌우(또는 위아래) 끝에 닿은 것은 둘레가 종이일 때 인정한다.
 *   6. 기울어 찍혀 모서리에 끼어든 종이를 빼려고, 덩어리가 70% 이상 차는 행·열만 남긴다.
 * 종이가 거의 안 보이거나(사진을 꽉 차게 찍음, 실제 피부를 찍음) 사각형 덩어리가 없으면 null — 전체를 그대로 쓴다.
 * 자동 결과는 시작점일 뿐이고 화면에서 상자를 손으로 고칠 수 있다(KioskAnalyze).
 */

/** 사진 폭·높이에 대한 비율(0~1)로 나타낸 사각형. */
export type NormRect = { x: number; y: number; w: number; h: number };

export const FULL_FRAME: NormRect = { x: 0, y: 0, w: 1, h: 1 };

/** 검출에 쓰는 축소 크기(긴 변). 이보다 크게 해도 결과는 같고 느려지기만 한다. */
export const DETECT_MAX_SIDE = 320;

const PAPER_LUM_RATIO = 0.72; // 종이 기준 밝기의 이 비율 이상이어야 종이(그늘진 종이까지)
const PAPER_CHROMA_TOL = 0.03; // 색도(r/(r+g+b), g/(r+g+b)) 차이 허용치 — 옅은 분홍 피부는 여기서 갈린다
const PAPER_TEXTURE_MAX = 14; // 3×3 평균 기울기 — 종이는 매끈하고, 피부 결·털은 거칠다
const MIN_PAPER_FRACTION = 0.12; // 종이가 이보다 적게 보이면 인쇄물 사진이 아니라고 본다
// 종이라고 본 곳의 결(중앙값). 찍은 종이는 1~5, 인쇄물이 아닌 사진에서 종이로 보인 옅은 피부는 대개 5~10 이다.
const MAX_PAPER_TEXTURE_MEDIAN = 6;
const MIN_REGION_FRACTION = 0.03; // 고를 덩어리의 최소 넓이
const MIN_FILL = 0.5; // 덩어리 넓이 ÷ 바깥 사각형 넓이 — 사각형다운가
const KEEP_COVERAGE = 0.7; // 가장자리 다듬기: 덩어리가 이 비율 이상 차는 행·열만 남긴다
const MIN_RING_PAPER = 0.5; // 화면 끝에 닿은 덩어리: 화면 안에 보이는 둘레의 이 비율 이상이 종이여야 사진으로 본다

export type DetectOptions = {
  /** 감싸는 직사각형을 채우는 최소 비율 — 화면 끝에 안 닿은 덩어리 / 닿은 덩어리(잘려서 덜 찬다). */
  minRectangularity?: number;
  minRectangularityTouching?: number;
  /** 시험·조정용: 종이를 잰 뒤 한 번 불린다. */
  onPaper?: (info: { fraction: number; texture: number; lum: number }) => void;
  /** 시험·조정용: 후보 덩어리마다 불린다. */
  onCandidate?: (info: { area: number; rectangularity: number; touchCount: number; lumStd: number; accepted: boolean }) => void;
};
const DEFAULT_MIN_RECT = 0.9;
const MIN_CONTENT_STD = 8; // 후보 영역 밝기 표준편차 — 이보다 작으면 단색 상자
const DEFAULT_MIN_RECT_TOUCHING = 0.8;

export function findPrintedPhoto(rgba: ArrayLike<number>, width: number, height: number,
                                 options: DetectOptions = {}): NormRect | null {
  const minRect = options.minRectangularity ?? DEFAULT_MIN_RECT;
  const minRectTouching = options.minRectangularityTouching ?? DEFAULT_MIN_RECT_TOUCHING;
  const W = width;
  const H = height;
  const N = W * H;
  if (W < 48 || H < 48) return null;

  // 1) 밝기·색도
  const lum = new Float32Array(N);
  const cr = new Float32Array(N);
  const cg = new Float32Array(N);
  for (let i = 0, p = 0; i < N; i++, p += 4) {
    const r = rgba[p];
    const g = rgba[p + 1];
    const b = rgba[p + 2];
    lum[i] = 0.299 * r + 0.587 * g + 0.114 * b;
    const s = r + g + b + 1;
    cr[i] = r / s;
    cg[i] = g / s;
  }

  // 2) 결(texture) — 이웃 밝기 차의 3×3 평균. 종이는 매끈하다.
  const grad = new Float32Array(N);
  for (let y = 1; y < H - 1; y++) {
    for (let x = 1; x < W - 1; x++) {
      const i = y * W + x;
      grad[i] = Math.abs(lum[i + 1] - lum[i - 1]) + Math.abs(lum[i + W] - lum[i - W]);
    }
  }
  const texture = boxMean(grad, W, H, 1);

  // 3) 종이 색 재기 — 매끈한 픽셀 가운데 밝은 상위 25% 의 중앙값(밝기·색도). 조명 색이 그대로 묻어 있다.
  const smoothLums: number[] = [];
  for (let i = 0; i < N; i++) if (texture[i] < PAPER_TEXTURE_MAX) smoothLums.push(lum[i]);
  if (smoothLums.length < N * MIN_PAPER_FRACTION) return null;
  smoothLums.sort((a, b) => a - b);
  const brightCut = smoothLums[Math.floor(smoothLums.length * 0.75)];
  const refCr: number[] = [];
  const refCg: number[] = [];
  const refLum: number[] = [];
  for (let i = 0; i < N; i++) {
    if (texture[i] < PAPER_TEXTURE_MAX && lum[i] >= brightCut) {
      refCr.push(cr[i]);
      refCg.push(cg[i]);
      refLum.push(lum[i]);
    }
  }
  const paperLum = median(refLum);
  const paperCr = median(refCr);
  const paperCg = median(refCg);
  if (paperLum < 90) return null; // 어두운 사진 — 흰 종이가 아니다

  // 4) 종이/내용 나누기
  const content = new Uint8Array(N);
  let paperCount = 0;
  for (let i = 0; i < N; i++) {
    const isPaper = lum[i] >= paperLum * PAPER_LUM_RATIO
      && Math.abs(cr[i] - paperCr) < PAPER_CHROMA_TOL
      && Math.abs(cg[i] - paperCg) < PAPER_CHROMA_TOL
      && texture[i] < PAPER_TEXTURE_MAX;
    if (isPaper) paperCount++;
    else content[i] = 1;
  }
  if (paperCount < N * MIN_PAPER_FRACTION) return null;
  const paperTexture: number[] = [];
  for (let i = 0; i < N; i++) if (!content[i]) paperTexture.push(texture[i]);
  const paperTextureMedian = median(paperTexture);
  options.onPaper?.({ fraction: paperCount / N, texture: paperTextureMedian, lum: paperLum });
  if (paperTextureMedian > MAX_PAPER_TEXTURE_MEDIAN) return null;

  // 5) 작은 구멍 메우기 — 내용에 둘러싸이고(화면 끝에 안 닿고) 작은 종이색 영역. 종이 자체는 커서 메우지 않는다.
  fillSmallHoles(content, W, H, Math.min(paperCount * 0.25, N * 0.15));

  // 6) 열림 연산 — 글자·테두리 선처럼 가는 내용을 지운다. 축소 사진 기준 두께 2px 안팎.
  const r = Math.max(1, Math.round(Math.min(W, H) / 120));
  const opened = dilate(erode(content, W, H, r), W, H, r);

  // 7) 이어진 덩어리 가운데 고르기
  const labels = new Int32Array(N);
  const stack = new Int32Array(N);
  const members = new Int32Array(N);
  let best: { label: number; area: number; x0: number; y0: number; x1: number; y1: number } | null = null;
  let next = 0;
  for (let start = 0; start < N; start++) {
    if (!opened[start] || labels[start]) continue;
    next++;
    let area = 0;
    let x0 = W, y0 = H, x1 = -1, y1 = -1;
    let touchL = false, touchR = false, touchT = false, touchB = false;
    let top = 0;
    stack[top++] = start;
    labels[start] = next;
    while (top > 0) {
      const i = stack[--top];
      members[area] = i;
      area++;
      const x = i % W;
      const y = (i - x) / W;
      if (x < x0) x0 = x;
      if (x > x1) x1 = x;
      if (y < y0) y0 = y;
      if (y > y1) y1 = y;
      if (x === 0) touchL = true;
      if (x === W - 1) touchR = true;
      if (y === 0) touchT = true;
      if (y === H - 1) touchB = true;
      if (x > 0 && opened[i - 1] && !labels[i - 1]) { labels[i - 1] = next; stack[top++] = i - 1; }
      if (x < W - 1 && opened[i + 1] && !labels[i + 1]) { labels[i + 1] = next; stack[top++] = i + 1; }
      if (y > 0 && opened[i - W] && !labels[i - W]) { labels[i - W] = next; stack[top++] = i - W; }
      if (y < H - 1 && opened[i + W] && !labels[i + W]) { labels[i + W] = next; stack[top++] = i + W; }
    }
    if (area < N * MIN_REGION_FRACTION) continue;
    const boxArea = (x1 - x0 + 1) * (y1 - y0 + 1);
    const aspect = (x1 - x0 + 1) / (y1 - y0 + 1);
    if (area / boxArea < MIN_FILL || aspect < 0.25 || aspect > 4) continue;
    // 화면 끝에 닿는 경우: 책상·종이 밖 배경은 세 군데 이상이나 이웃한 두 군데(구석)에 닿는다.
    // 가까이 찍은 인쇄 사진은 마주 보는 두 끝(좌우 또는 위아래)까지만 닿고, 화면 안에 보이는 둘레는 종이다.
    const touchCount = Number(touchL) + Number(touchR) + Number(touchT) + Number(touchB);
    if (touchCount > 0) {
      const opposite = touchCount === 1 || (touchCount === 2 && ((touchL && touchR) || (touchT && touchB)));
      if (!opposite) continue;
      if (ringPaperRatio(content, W, H, x0, y0, x1, y1, Math.max(2, r * 2)) < MIN_RING_PAPER) continue;
    }
    const rectangularity = rotatedRectangularity(members, area, W);
    // 내용 없는 단색 사각형(가림 상자·휴대폰 화면 등)은 사진이 아니다 — 인쇄된 사진은 안에 명암이 있다.
    const lumStd = stdOf(lum, members, area);
    const accepted = rectangularity >= (touchCount > 0 ? minRectTouching : minRect) && lumStd >= MIN_CONTENT_STD;
    options.onCandidate?.({ area: area / N, rectangularity, touchCount, lumStd, accepted });
    if (!accepted) continue;
    if (!best || area > best.area) best = { label: next, area, x0, y0, x1, y1 };
  }
  if (!best) return null;

  // 8) 가장자리 다듬기 — 기울어 찍혀 모서리에 끼어든 종이를 뺀다.
  let { x0, y0, x1, y1 } = best;
  const inBest = (x: number, y: number) => labels[y * W + x] === best.label;
  const rowCoverage = (y: number) => {
    let c = 0;
    for (let x = x0; x <= x1; x++) if (inBest(x, y)) c++;
    return c / (x1 - x0 + 1);
  };
  const colCoverage = (x: number) => {
    let c = 0;
    for (let y = y0; y <= y1; y++) if (inBest(x, y)) c++;
    return c / (y1 - y0 + 1);
  };
  for (let pass = 0; pass < 2; pass++) {
    while (y0 < y1 && rowCoverage(y0) < KEEP_COVERAGE) y0++;
    while (y1 > y0 && rowCoverage(y1) < KEEP_COVERAGE) y1--;
    while (x0 < x1 && colCoverage(x0) < KEEP_COVERAGE) x0++;
    while (x1 > x0 && colCoverage(x1) < KEEP_COVERAGE) x1--;
  }
  const w = x1 - x0 + 1;
  const h = y1 - y0 + 1;
  if (w * h < N * MIN_REGION_FRACTION) return null;

  return clampRect({ x: x0 / W, y: y0 / H, w: w / W, h: h / H });
}

/** 정규화 사각형을 [0,1] 안으로 넣고 최소 크기를 지킨다. */
export function clampRect(rect: NormRect, minSize = 0.05): NormRect {
  const w = Math.min(1, Math.max(minSize, rect.w));
  const h = Math.min(1, Math.max(minSize, rect.h));
  const x = Math.min(1 - w, Math.max(0, rect.x));
  const y = Math.min(1 - h, Math.max(0, rect.y));
  return { x, y, w, h };
}

export function isFullFrame(rect: NormRect): boolean {
  return rect.x <= 0.001 && rect.y <= 0.001 && rect.w >= 0.999 && rect.h >= 0.999;
}

/**
 * 덩어리 넓이 ÷ 덩어리를 감싸는 가장 작은 직사각형 넓이. ±15° 를 1° 간격으로 돌려 본다(손으로 찍은 기울기 범위).
 * 직사각형은 1 에 가깝고(축소·흐림으로 0.9 대), 타원은 π/4 ≈ 0.79, 불규칙한 병변은 더 낮다.
 */
function rotatedRectangularity(members: Int32Array, count: number, W: number): number {
  let bestArea = Infinity;
  for (let deg = -15; deg <= 15; deg++) {
    const t = (deg * Math.PI) / 180;
    const c = Math.cos(t);
    const s = Math.sin(t);
    let u0 = Infinity, u1 = -Infinity, v0 = Infinity, v1 = -Infinity;
    for (let k = 0; k < count; k++) {
      const i = members[k];
      const x = i % W;
      const y = (i - x) / W;
      const u = x * c + y * s;
      const v = y * c - x * s;
      if (u < u0) u0 = u;
      if (u > u1) u1 = u;
      if (v < v0) v0 = v;
      if (v > v1) v1 = v;
    }
    const a = (u1 - u0 + 1) * (v1 - v0 + 1);
    if (a < bestArea) bestArea = a;
  }
  return count / bestArea;
}

/** 상자 바깥 고리(두께 t, 화면 안쪽만)에서 종이(내용 아님)인 비율. */
function ringPaperRatio(content: Uint8Array, W: number, H: number,
                        x0: number, y0: number, x1: number, y1: number, t: number): number {
  let paper = 0;
  let total = 0;
  const X0 = Math.max(0, x0 - t), X1 = Math.min(W - 1, x1 + t);
  const Y0 = Math.max(0, y0 - t), Y1 = Math.min(H - 1, y1 + t);
  for (let y = Y0; y <= Y1; y++) {
    for (let x = X0; x <= X1; x++) {
      if (x >= x0 && x <= x1 && y >= y0 && y <= y1) continue;
      total++;
      if (!content[y * W + x]) paper++;
    }
  }
  return total === 0 ? 0 : paper / total;
}

/** 내용(1)에 둘러싸인 종이(0) 영역 가운데 화면 끝에 닿지 않고 maxArea 보다 작은 것을 내용으로 바꾼다. */
function fillSmallHoles(content: Uint8Array, W: number, H: number, maxArea: number): void {
  const seen = new Uint8Array(W * H);
  const stack = new Int32Array(W * H);
  const members: number[] = [];
  for (let start = 0; start < W * H; start++) {
    if (content[start] || seen[start]) continue;
    members.length = 0;
    let touches = false;
    let top = 0;
    stack[top++] = start;
    seen[start] = 1;
    while (top > 0) {
      const i = stack[--top];
      members.push(i);
      const x = i % W;
      const y = (i - x) / W;
      if (x === 0 || y === 0 || x === W - 1 || y === H - 1) touches = true;
      if (x > 0 && !content[i - 1] && !seen[i - 1]) { seen[i - 1] = 1; stack[top++] = i - 1; }
      if (x < W - 1 && !content[i + 1] && !seen[i + 1]) { seen[i + 1] = 1; stack[top++] = i + 1; }
      if (y > 0 && !content[i - W] && !seen[i - W]) { seen[i - W] = 1; stack[top++] = i - W; }
      if (y < H - 1 && !content[i + W] && !seen[i + W]) { seen[i + W] = 1; stack[top++] = i + W; }
    }
    if (!touches && members.length < maxArea) for (const i of members) content[i] = 1;
  }
}

function stdOf(values: Float32Array, members: Int32Array, count: number): number {
  let sum = 0;
  for (let k = 0; k < count; k++) sum += values[members[k]];
  const mean = sum / count;
  let sq = 0;
  for (let k = 0; k < count; k++) {
    const d = values[members[k]] - mean;
    sq += d * d;
  }
  return Math.sqrt(sq / count);
}

function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function boxMean(src: Float32Array, W: number, H: number, r: number): Float32Array {
  const tmp = new Float32Array(W * H);
  const out = new Float32Array(W * H);
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let s = 0;
      let c = 0;
      for (let k = Math.max(0, x - r); k <= Math.min(W - 1, x + r); k++) { s += src[y * W + k]; c++; }
      tmp[y * W + x] = s / c;
    }
  }
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let s = 0;
      let c = 0;
      for (let k = Math.max(0, y - r); k <= Math.min(H - 1, y + r); k++) { s += tmp[k * W + x]; c++; }
      out[y * W + x] = s / c;
    }
  }
  return out;
}

/** 정사각형 구조 요소(반지름 r)의 침식 — 가로·세로로 나눠 한다. */
function erode(mask: Uint8Array, W: number, H: number, r: number): Uint8Array {
  return morph(mask, W, H, r, true);
}

function dilate(mask: Uint8Array, W: number, H: number, r: number): Uint8Array {
  return morph(mask, W, H, r, false);
}

function morph(mask: Uint8Array, W: number, H: number, r: number, isErode: boolean): Uint8Array {
  const tmp = new Uint8Array(W * H);
  const out = new Uint8Array(W * H);
  const pick = (acc: number, v: number) => (isErode ? Math.min(acc, v) : Math.max(acc, v));
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let acc = isErode ? 1 : 0;
      for (let k = x - r; k <= x + r; k++) {
        const v = k < 0 || k >= W ? (isErode ? 0 : 0) : mask[y * W + k];
        acc = pick(acc, v);
      }
      tmp[y * W + x] = acc;
    }
  }
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let acc = isErode ? 1 : 0;
      for (let k = y - r; k <= y + r; k++) {
        const v = k < 0 || k >= H ? 0 : tmp[k * W + x];
        acc = pick(acc, v);
      }
      out[y * W + x] = acc;
    }
  }
  return out;
}

/**
 * 고른 사진에서 인쇄된 사진 부분을 찾는다(브라우저 전용). 사진 크기(EXIF 방향 반영)도 함께 돌려준다.
 * 사진을 읽지 못하면 null — 화면은 자르기 없이 전처럼 동작한다.
 */
export async function detectPrintedPhotoInFile(
  file: Blob,
): Promise<{ rect: NormRect | null; width: number; height: number } | null> {
  if (typeof createImageBitmap !== "function") return null;
  let bitmap: ImageBitmap | null = null;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    const { width, height } = bitmap;
    const scale = Math.min(1, DETECT_MAX_SIDE / Math.max(width, height));
    const w = Math.max(1, Math.round(width * scale));
    const h = Math.max(1, Math.round(height * scale));
    const ctx = drawDownscaled(bitmap, width, height, w, h);
    if (!ctx) return { rect: null, width, height };
    return { rect: findPrintedPhoto(ctx.getImageData(0, 0, w, h).data, w, h), width, height };
  } catch {
    return null;
  } finally {
    bitmap?.close();
  }
}

/**
 * 반씩 여러 번 줄여 그린다. 1200만 화소 사진을 한 번에 320px 로 그리면 픽셀을 건너뛰며 집어 와
 * 종이의 잡티(센서 노이즈)가 그대로 남는다 — 종이가 거칠어 보여 인쇄물로 알아보지 못한다.
 * 반씩 줄이면 이웃 픽셀이 평균돼, 시험에 쓴 축소(PIL BILINEAR)와 같은 결과가 된다.
 */
function drawDownscaled(source: CanvasImageSource, sw: number, sh: number, tw: number, th: number): CanvasRenderingContext2D | null {
  let src: CanvasImageSource = source;
  let w = sw;
  let h = sh;
  while (w / 2 >= tw && h / 2 >= th) {
    const half = document.createElement("canvas");
    half.width = Math.max(1, Math.round(w / 2));
    half.height = Math.max(1, Math.round(h / 2));
    const hc = half.getContext("2d");
    if (!hc) return null;
    hc.imageSmoothingQuality = "high";
    hc.drawImage(src, 0, 0, half.width, half.height);
    src = half;
    w = half.width;
    h = half.height;
  }
  const out = document.createElement("canvas");
  out.width = tw;
  out.height = th;
  const ctx = out.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(src, 0, 0, tw, th);
  return ctx;
}
