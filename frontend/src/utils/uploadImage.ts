import type { NormRect } from "./printedPhotoCrop";

/**
 * 올리기 전에 사진을 줄인다 — 긴 변 maxSide 픽셀의 JPEG 로.
 *
 * 휴대폰 원본(12MP, 수 MB)을 그대로 올리면 세 군데가 무거워진다. 행사장 와이파이에서 업로드가 느려지고,
 * 백엔드는 같은 사진을 base64·JSON 으로 몇 벌씩 복사하며, FastAPI 는 원본 해상도로 디코딩해 히트맵을 그린다.
 * 잃는 것은 없다. 모델 입력은 224·384 이고 서버가 저장하는 히트맵도 긴 변 2048 이 상한이다(fastapi/main.py).
 *
 * 덤으로 두 가지가 풀린다.
 *   - 방향: createImageBitmap 이 EXIF 회전을 적용한 그림을 주고, 새 JPEG 에는 EXIF 가 없다 — 서버의
 *     exif_transpose 와 같은 결과다.
 *   - HEIC: 브라우저가 열 수 있는 형식이면 무엇이든 JPEG 가 되어 나간다(서버는 HEIC 를 읽지 못한다).
 *
 * crop 을 주면 그 부분만 잘라 올린다(사진 폭·높이에 대한 비율, EXIF 방향을 적용한 그림 기준).
 * 키오스크가 인쇄물 사진에서 흰 여백·글자를 뺀 부분만 분석하게 할 때 쓴다(utils/printedPhotoCrop.ts).
 *
 * 줄이지 못하면(디코딩 실패·오래된 브라우저) 원본을 그대로 돌려준다 — 자르기도 하지 못한다. 판단은 서버에 맡긴다.
 */
export async function shrinkForUpload(file: File, maxSide = 2048, quality = 0.9, crop?: NormRect): Promise<File> {
  if (!file.type.startsWith("image/") || typeof createImageBitmap !== "function") return file;
  try {
    const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    const sx = crop ? Math.min(bitmap.width - 1, Math.max(0, Math.round(crop.x * bitmap.width))) : 0;
    const sy = crop ? Math.min(bitmap.height - 1, Math.max(0, Math.round(crop.y * bitmap.height))) : 0;
    const sw = crop ? Math.max(1, Math.min(bitmap.width - sx, Math.round(crop.w * bitmap.width))) : bitmap.width;
    const sh = crop ? Math.max(1, Math.min(bitmap.height - sy, Math.round(crop.h * bitmap.height))) : bitmap.height;
    const scale = Math.min(1, maxSide / Math.max(sw, sh));
    const width = Math.max(1, Math.round(sw * scale));
    const height = Math.max(1, Math.round(sh * scale));

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d");
    if (!context) {
      bitmap.close();
      return file;
    }
    context.drawImage(bitmap, sx, sy, sw, sh, 0, 0, width, height);
    bitmap.close();

    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
    if (!blob) return file;
    const baseName = file.name.replace(/\.[^.]+$/, "") || "photo";
    return new File([blob], `${baseName}${crop ? "-crop" : ""}.jpg`, { type: "image/jpeg" });
  } catch {
    return file;
  }
}
