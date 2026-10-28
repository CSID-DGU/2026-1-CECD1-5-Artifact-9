"""
화면에 나가는 히트맵과 확대 영역이 **병변 자리**를 짚는지, 서빙 코드 그대로 정답 마스크로 잰다.

evaluate_cam.py 와 무엇이 다른가
-------------------------------
evaluate_cam.py 는 격자 CAM 을 재고 분류 정답(holdout.csv 의 라벨)이 필요하다. 이 스크립트는
라벨 없이 마스크만 있으면 되고, **표시용 맵**(_display_map — 흐림까지 거친, 사용자가 보는 그림)과
**확대 영역**(_propose_crops·_crop_forward·_crop_layercams)을 잰다. main.py 의 함수를 그대로
부르므로 서빙에서 바뀐 것이 여기에 자동으로 반영된다.

데이터: ISIC 2018 Task 1 (병변 분할) 검증 100장 + 시험 1,000장. 홀드아웃과 겹치지 않는다.
  https://isic-archive.s3.amazonaws.com/challenges/2018/ISIC2018_Task1-2_Validation_Input.zip
  https://isic-archive.s3.amazonaws.com/challenges/2018/ISIC2018_Task1_Validation_GroundTruth.zip
  (Test 도 같은 이름 규칙) — 이미지·마스크를 각각 한 폴더에 풀어 둔다. 저장소에는 넣지 않는다.

지표
----
  전체 히트맵   pointing  최댓점이 병변 안인가(짧은 변 1.5% 여유)
                energy    히트맵 에너지 중 병변 안 비율. 무작위 기준은 병변 면적 비율(평균 약 0.28)
                조각       0.5 이상인 곳이 몇 덩어리인가. "빨간 점만 흩어진" 그림이면 커진다
  확대 영역     적중       확대 영역에 병변이 10% 이상 들어가는가 — 순위별
  확대 히트맵   pointing·energy  확대 영역 안에서 (병변이 5~95% 인 영역만)

사용법
------
    docker compose run --rm -v /path/to/isic2018:/data:ro fastapi \\
        python tests/evaluate_heatmap_loc.py --image-dir /data/images --mask-dir /data/masks
    ... --limit 200            # 빨리 감 잡기
    ... --json out.json        # 기록 남기기 (baselines/README.md 의 표와 비교)
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

FASTAPI_DIR = Path(__file__).resolve().parent.parent
os.chdir(FASTAPI_DIR)
sys.path.insert(0, str(FASTAPI_DIR))
os.environ.setdefault("INTERNAL_API_SECRET", "evaluate_heatmap_loc.py-does-not-serve")

import numpy as np  # noqa: E402
import torch  # noqa: E402
import torch.nn.functional as F  # noqa: E402
from PIL import Image, ImageOps  # noqa: E402

import main as serving  # noqa: E402

TM = serving.TASK_MODELS[serving.DEFAULT_TASK]
MASK_SUFFIXES = ("_segmentation.png", ".png")


def find_mask(mask_dir: Path, stem: str) -> Path | None:
    for suffix in MASK_SUFFIXES:
        if (mask_dir / f"{stem}{suffix}").is_file():
            return mask_dir / f"{stem}{suffix}"
    return None


def mask_like(mask: Image.Image, disp: torch.Tensor) -> torch.Tensor:
    """마스크를 표시용 맵 해상도로 (1,1,h,w) 0/1."""
    h, w = disp.shape[-2:]
    m = np.asarray(mask.convert("L").resize((w, h), Image.NEAREST)) > 0
    return torch.from_numpy(m.astype(np.float32))[None, None]


def components(hot: np.ndarray) -> int:
    seen = np.zeros_like(hot, dtype=bool)
    count = 0
    for y, x in zip(*np.nonzero(hot)):
        if not seen[y, x]:
            seen |= serving._flood(hot & ~seen, int(y), int(x))
            count += 1
    return count


def loc(disp: torch.Tensor, mask: torch.Tensor) -> dict:
    tol = max(1, round(0.015 * min(disp.shape[-2:])))
    near = F.max_pool2d(mask, kernel_size=2 * tol + 1, stride=1, padding=tol)
    idx = int(torch.argmax(disp))
    total = float(disp.sum())
    return {
        "pointing": float(near.flatten()[idx]),
        "energy": float((disp * mask).sum()) / total if total > 0 else 0.0,
        "pieces": components((disp >= 0.5).squeeze().numpy()),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--image-dir", type=Path, required=True)
    ap.add_argument("--mask-dir", type=Path, required=True)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--json", type=Path, default=None)
    args = ap.parse_args()

    images = sorted(p for p in args.image_dir.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
    pairs = [(p, m) for p in images if (m := find_mask(args.mask_dir, p.stem))][: args.limit]
    if not pairs:
        print("마스크 짝이 있는 이미지가 없습니다.")
        return 1

    full, crop_heat, area = [], [], []
    by_rank: dict[int, list[float]] = {}
    n_crops = []
    t0 = time.time()
    for n, (img_path, mask_path) in enumerate(pairs, 1):
        image = ImageOps.exif_transpose(Image.open(img_path)).convert("RGB")
        mask_img = Image.open(mask_path)
        width, height = image.size
        tensor = TM.transform(image).unsqueeze(0).to(serving.device)
        with TM.lock:
            logits, cam, _ = serving._compute_gradcam(tensor, TM)
        if cam is None:
            continue
        pred = int(logits.argmax(dim=1))
        disp, boxes, crops = serving._crop_regions(cam, image)
        full.append(loc(disp, mask_like(mask_img, disp)))
        full_mask = np.asarray(mask_img.convert("L").resize((width, height), Image.NEAREST)) > 0
        area.append(float(full_mask.mean()))
        n_crops.append(len(boxes))
        if crops and TM.reinfer_crops:
            with TM.lock:
                cams = serving._crop_layercams(serving._crop_forward(crops, TM), pred)
        for rank, (box, crop) in enumerate(zip(boxes, crops), 1):
            x0, y0, x1, y1 = (round(v) for v in box[:4])
            frac = float(full_mask[y0:y1, x0:x1].mean()) if y1 > y0 and x1 > x0 else 0.0
            by_rank.setdefault(rank, []).append(frac)
            if TM.reinfer_crops and 0.05 <= frac <= 0.95:
                cdisp = serving._display_map(cams[rank - 1], *crop.size, smooth=serving._CROP_SMOOTH)
                crop_heat.append(loc(cdisp, mask_like(mask_img.crop((x0, y0, x1, y1)), cdisp)))
        if n % 100 == 0:
            print(f"  {n}/{len(pairs)}  {time.time() - t0:.0f}s", flush=True)

    def mean(rows, key):
        return round(float(np.mean([r[key] for r in rows])), 4) if rows else None

    result = {
        "heatmap_style": serving.HEATMAP_STYLE,
        "model_version": TM.model_version,
        "cam_layers": TM.cam_layer_names,
        "images": len(full),
        "lesion_area": round(float(np.mean(area)), 4),
        "full": {k: mean(full, k) for k in ("pointing", "energy", "pieces")},
        "crops_per_image": round(float(np.mean(n_crops)), 3),
        "crop_hit_by_rank": {r: {"n": len(v), "hit10": round(float(np.mean([f >= 0.10 for f in v])), 4)}
                             for r, v in sorted(by_rank.items())},
        "crop_heatmap": {"n": len(crop_heat), **{k: mean(crop_heat, k) for k in ("pointing", "energy", "pieces")}},
    }
    print(json.dumps(result, ensure_ascii=False, indent=1))
    if args.json:
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
