"""
히트맵(CAM)이 **실제로 병변을 짚는지**를 정답 마스크로 재는 평가 하네스.

왜 필요한가
----------
tests/evaluate.py 는 확률만 본다. 그래서 "모델이 맞혔는가"는 답할 수 있지만
"왜 맞혔다고 보여주는 그림이 맞는가"는 답할 수 없었다. 실제로 배포된 화면에서
  - 병변 부위가 짙은 파란색으로 나오고, 주변 정상 피부가 빨갛고,
  - 같은 시스템인데 사진마다 색 기준이 달라 보이는
문제가 있었는데, 고친 뒤에도 "좋아진 것 같다"는 눈대중밖에 근거가 없었다.
이 스크립트가 그 근거를 숫자로 만든다.

evaluate.py 와 같은 패턴을 따른다 — --json 으로 기준선을 떠 두고, 코드를 고친 뒤
--compare 로 나란히 본다. 전처리와 모델은 evaluate.py 를 임포트해 그대로 쓴다
(main.py 의 transform/model/_compute_gradcam 을 직접 호출하므로, 서빙에서 바뀐 것이
여기에 자동으로 반영된다 — 값을 복사해 두면 언젠가 한쪽만 바뀌어 거짓을 측정한다).

**측정 가능 범위의 한계 — 반드시 읽을 것**
------------------------------------------
정답 마스크가 있는 것은 HAM10000(더모스코프) 뿐이다. PAD-UFES-20(스마트폰)과
SCIN(염증성)에는 공개 병변 세그멘테이션이 없다. 즉 **이 하네스로는 폰카 사진의
로컬라이제이션 품질을 측정할 수 없다** — 정작 가장 문제가 되는 쪽이 그쪽이다.
여기서 좋아졌다고 해서 키오스크 사진이 좋아졌다는 뜻이 아니다.
그쪽은 부스에서 찍은 사진에 손으로 마스크를 칠해 보조 세트를 만들어야 한다.

마스크 준비
----------
HAM10000 공식 병변 세그멘테이션(Harvard Dataverse / ISIC 2018 Task 1 GT):
파일명 규칙은 `ISIC_0025837_segmentation.png` 이다. 홀드아웃 이미지와 마찬가지로
저장소에 커밋하지 않는다(tests/README.md 참고) — 경로만 인자로 받는다.

사용법
------
    # 기준선 뜨기 (코드 고치기 전)
    docker compose run --rm -v /path/to/ham:/data:ro fastapi \
        python tests/evaluate_cam.py \
            --csv tests/baselines/holdout.csv \
            --image-dir /data/images --mask-dir /data/masks \
            --json tests/baselines/cam-before.json

    # 고친 뒤 비교
    ... python tests/evaluate_cam.py --csv ... --image-dir ... --mask-dir ... \
            --compare tests/baselines/cam-before.json

    # 빨리 감 잡기 (앞 100장만)
    ... --limit 100

지표를 어떻게 읽는가
--------------------
  pointing game  CAM 최댓값 지점이 마스크 안인가. "AI 가 가리킨 한 점이 병변인가"
                 — 가장 직관적이고, 임상적으로도 가장 의미가 크다.
  energy ratio   마스크 안에 들어간 CAM 에너지 비율. 배경으로 새는 양을 본다.
                 "주변 정상 피부가 빨갛다"는 증상의 주 지표다.
  IoU@0.5max     0.5*max 로 이진화한 CAM 과 마스크의 IoU. 모양까지 맞는가.
  면적 4분위     위 셋을 병변 면적 비율로 쪼갠다. **작은 병변에서 급락하는지**가
                 핵심이다 — 멀리서 찍은 폰카 사진이 바로 그 상황이기 때문이다.
  정답/오답 분리 분류가 틀린 건의 CAM 이 더 흩어지는지 본다.
"""

import argparse
import json
import sys
from pathlib import Path

# --- evaluate.py 를 임포트해 준비 작업을 위임한다 (main 임포트보다 먼저) -------
#
# evaluate.py 는 모듈 레벨에서 fastapi/ 로 chdir 하고, main.py 가 기동을 거부하지
# 않도록 INTERNAL_API_SECRET 대체값을 넣고, main 을 임포트한다. 그 준비 코드를
# 여기에 복사하면 한쪽만 고쳐질 날이 온다. 그래서 복사하지 않고 임포트한다.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate  # noqa: E402

import numpy as np  # noqa: E402
import torch  # noqa: E402
import torch.nn.functional as F  # noqa: E402
from PIL import Image, ImageOps  # noqa: E402

serving = evaluate.serving
CLASSES = evaluate.CLASSES
lj, rj, pct = evaluate.lj, evaluate.rj, evaluate.pct

MASK_SUFFIXES = ("_segmentation.png", "_segmentation.jpg", ".png", ".jpg")

# 면적 4분위 경계는 고정값이다. 데이터에서 분위를 계산해 잡으면 홀드아웃이 바뀔 때마다
# 구간이 움직여 --compare 가 다른 모집단을 비교하게 된다.
AREA_BINS = [(0.0, 0.05), (0.05, 0.15), (0.15, 0.35), (0.35, 1.01)]


def _resolve_mask(mask_dirs: list[Path], image_id: str) -> Path | None:
    """`ISIC_0025837.jpg` → `ISIC_0025837_segmentation.png` 등으로 찾아낸다."""
    stem = Path(image_id).stem
    for mask_dir in mask_dirs:
        for suffix in MASK_SUFFIXES:
            candidate = mask_dir / f"{stem}{suffix}"
            if candidate.is_file():
                return candidate
    return None


def cam_for(path: Path) -> tuple[torch.Tensor | None, int, dict | None]:
    """
    한 장의 CAM 과 예측 클래스를 구한다. 서빙과 **같은 경로**를 타야 하므로
    main.run_inference 가 쓰는 함수들을 그대로 호출한다 (transform → _compute_gradcam).

    EXIF 처리까지 서빙과 같게 맞춘다 — 여기서 빼먹으면 서빙이 보는 것과 다른
    이미지를 평가하게 된다.
    """
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    tensor = serving.transform(image).unsqueeze(0).to(serving.device)
    # 단일 스레드지만 _compute_gradcam 의 호출 계약(락을 잡은 채 호출)을 지킨다.
    with serving._model_lock:
        logits, cam, quality = serving._compute_gradcam(tensor)
    return cam, int(logits.argmax(dim=1).item()), quality


def metrics_for(cam: torch.Tensor, mask: torch.Tensor) -> dict:
    """
    CAM 하나와 마스크 하나로 세 지표를 계산한다.

    CAM 은 마스크 해상도로 업샘플한다 — 서빙이 원본 해상도로 업샘플해 화면에 뿌리는
    것과 같은 연산이다. 반대로 마스크를 격자 크기로 줄이면 실제로 사용자가 보는
    그림이 아닌 것을 재게 된다.
    """
    h, w = mask.shape
    up = F.interpolate(cam, size=(h, w), mode="bilinear", align_corners=False)
    c = up.squeeze().clamp(min=0.0)

    total = float(c.sum())
    if total <= 1e-8:
        # CAM 이 전부 0 — "모델이 어디도 근거로 보지 않았다". 적중 실패로 센다.
        return {"hit": 0.0, "energy": 0.0, "iou": 0.0}

    peak = int(torch.argmax(c))
    hit = float(mask.flatten()[peak])

    energy = float((c * mask).sum()) / total

    binary = c >= 0.5 * float(c.max())
    m = mask > 0.5
    union = float((binary | m).sum())
    iou = float((binary & m).sum()) / union if union > 0 else 0.0

    return {"hit": hit, "energy": energy, "iou": iou}


def _agg(rows: list[dict]) -> dict:
    if not rows:
        return {"n": 0, "pointing_game": None, "energy_ratio": None, "iou": None}
    n = len(rows)
    return {
        "n": n,
        "pointing_game": sum(r["hit"] for r in rows) / n,
        "energy_ratio": sum(r["energy"] for r in rows) / n,
        "iou": sum(r["iou"] for r in rows) / n,
    }


def evaluate_cam(samples, limit: int | None) -> dict:
    if limit:
        samples = samples[:limit]

    rows, qualities = [], []
    total = len(samples)
    for i, (image_path, mask_path, label) in enumerate(samples, 1):
        cam, pred, quality = cam_for(image_path)
        if cam is None:
            print(f"\n  ⚠ CAM 계산 실패, 건너뜀: {image_path.name}")
            continue

        mask_img = Image.open(mask_path).convert("L")
        mask = torch.from_numpy(np.array(mask_img, dtype=np.float32))
        mask = (mask > 127.5).float()
        area = float(mask.mean())
        if area <= 0.0:
            print(f"\n  ⚠ 빈 마스크, 건너뜀: {mask_path.name}")
            continue

        row = metrics_for(cam, mask)
        row["area"] = area
        row["correct"] = CLASSES[pred] == label
        rows.append(row)
        if quality:
            qualities.append(quality)

        print(f"\r  CAM 평가 {i}/{total}", end="", flush=True)
    print()

    result = {
        "model_version": serving.MODEL_VERSION,
        "cam_layers": serving.CAM_LAYER_NAMES,
        "heatmap_display_floor": serving.HEATMAP_DISPLAY_FLOOR,
        "sample_count": len(rows),
        "classes": CLASSES,
        **_agg(rows),
        "by_area": [
            {"range": f"{lo:.0%}~{hi:.0%}",
             **_agg([r for r in rows if lo <= r["area"] < hi])}
            for lo, hi in AREA_BINS
        ],
        "by_correctness": {
            "correct": _agg([r for r in rows if r["correct"]]),
            "incorrect": _agg([r for r in rows if not r["correct"]]),
        },
    }
    if qualities:
        n = len(qualities)
        result["quality"] = {
            "focus_area": sum(q["focus_area"] for q in qualities) / n,
            "peak_ratio": sum(q["peak_ratio"] for q in qualities) / n,
        }
    return result


# =============================================
# 충실도 (마스크 없이, 모델 자신에게 묻는다)
# =============================================

FAITH_STEPS = torch.linspace(0, 1, 11)


def faithfulness_for(path: Path) -> dict | None:
    """
    정답 마스크 없이 "CAM 이 정말 근거를 짚었는가"를 모델 자신에게 묻는다.

      deletion  CAM 이 높다고 한 곳부터 차례로 흐리게 지우며 예측 클래스 확률을 본다.
                진짜 근거였다면 확률이 빨리 무너진다 → AUC 가 **낮아야** 좋다.
      insertion 전부 흐린 그림에서 CAM 이 높은 곳부터 되살린다 → AUC 가 **높아야** 좋다.
      score     insertion - deletion. 음수면 "CAM 이 가리킨 곳은 근거가 아니었다"는 뜻이다.

    이 지표는 CAM 의 **픽셀 순위만** 쓴다(각 단계의 마스크가 "상위 k 개 픽셀"). 그래서
    _norm01 의 분위 선택, HEATMAP_DISPLAY_FLOOR, alpha 예산 같은 표시용 결정에 영향받지
    않는다 — 알고리즘·레이어 선택만 평가한다.

    한 방향만 좋아지는 후보를 걸러내려면 score 만 보지 말고 del/ins 를 같이 봐야 한다.
    실측에서 아주 얕은 층(56x56)은 insertion 만 오르고 deletion 은 나빠졌다 — 클래스
    근거가 아니라 엣지·질감을 드러내 insertion 쪽만 유리해지는 알려진 실패 양상이다.

    기준 영상은 16 배 축소 후 복원한 블러다. cv2 없이 만들 수 있고(이 저장소는 ARM
    이미지 크기 때문에 의존성을 최소로 둔다), 회색 채움보다 저주파 맥락을 남겨 준다.
    """
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    x = serving.transform(image).unsqueeze(0).to(serving.device)
    with serving._model_lock:
        logits, cam, _ = serving._compute_gradcam(x)
    if cam is None:
        return None
    pred = int(logits.argmax(dim=1).item())

    blur = F.interpolate(
        F.interpolate(x, scale_factor=1 / 16, mode="area"),
        size=x.shape[-2:], mode="bilinear", align_corners=False)
    up = F.interpolate(cam.clamp(min=0), size=x.shape[-2:],
                       mode="bilinear", align_corners=False).flatten()
    order = torch.argsort(up, descending=True)
    npx = up.numel()

    dels, inss = [], []
    for frac in FAITH_STEPS:
        k = int(round(float(frac) * npx))
        msk = torch.zeros(npx)
        if k:
            msk[order[:k]] = 1.0
        msk = msk.view(1, 1, *x.shape[-2:])
        dels.append(x * (1 - msk) + blur * msk)
        inss.append(blur * (1 - msk) + x * msk)
    with torch.no_grad():
        pd = F.softmax(serving.model(torch.cat(dels)), dim=1)[:, pred]
        pi = F.softmax(serving.model(torch.cat(inss)), dim=1)[:, pred]

    deletion = float(torch.trapz(pd, FAITH_STEPS))
    insertion = float(torch.trapz(pi, FAITH_STEPS))
    return {"deletion": deletion, "insertion": insertion,
            "score": insertion - deletion, "pred": pred}


def evaluate_faithfulness(samples, limit: int | None) -> dict:
    if limit:
        samples = samples[:limit]
    rows = []
    total = len(samples)
    for i, (image_path, label) in enumerate(samples, 1):
        row = faithfulness_for(image_path)
        if row is None:
            print(f"\n  ⚠ CAM 계산 실패, 건너뜀: {image_path.name}")
            continue
        row["correct"] = CLASSES[row["pred"]] == label
        rows.append(row)
        print(f"\r  충실도 평가 {i}/{total}", end="", flush=True)
    print()

    def agg(rs):
        if not rs:
            return {"n": 0, "deletion": None, "insertion": None, "score": None}
        n = len(rs)
        return {"n": n,
                "deletion": sum(r["deletion"] for r in rs) / n,
                "insertion": sum(r["insertion"] for r in rs) / n,
                "score": sum(r["score"] for r in rs) / n}

    return {
        "mode": "faithfulness",
        "model_version": serving.MODEL_VERSION,
        "cam_layers": serving.CAM_LAYER_NAMES,
        "heatmap_display_floor": serving.HEATMAP_DISPLAY_FLOOR,
        "sample_count": len(rows),
        "classes": CLASSES,
        **agg(rows),
        "by_correctness": {"correct": agg([r for r in rows if r["correct"]]),
                           "incorrect": agg([r for r in rows if not r["correct"]])},
    }


def print_faith_report(result):
    print("=" * 74)
    print("  CAM 충실도 (정답 마스크 불필요 — 폰카·염증 사진에도 쓸 수 있다)")
    print(f"    모델      {result['model_version']}")
    print(f"    CAM 레이어 {', '.join(result['cam_layers'])}")
    print(f"    표본      {result['sample_count']}장")
    print("=" * 74)
    print()
    print("  " + lj("", 24) + rj("장수", 6) + rj("deletion↓", 11)
          + rj("insertion↑", 12) + rj("score↑", 10))
    print("  " + "-" * 63)

    def line(name, b):
        if not b["n"]:
            print("  " + lj(name, 24) + rj("0", 6) + rj("-", 11) + rj("-", 12) + rj("-", 10))
            return
        print("  " + lj(name, 24) + rj(str(b["n"]), 6)
              + rj(f"{b['deletion']:.3f}", 11) + rj(f"{b['insertion']:.3f}", 12)
              + rj(f"{b['score']:+.3f}", 10))

    line("전체", {k: result[k] for k in ("n", "deletion", "insertion", "score")})
    print()
    print("  분류 정답/오답별")
    for key, name in (("correct", "분류 정답"), ("incorrect", "분류 오답")):
        line(name, result["by_correctness"][key])
    print()
    print("  score 가 음수면 CAM 이 가리킨 곳을 지워도 예측이 안 무너진다는 뜻 —")
    print("  그 히트맵은 근거를 보여 주는 게 아니다. 참고: 측정 당시 변경 전 코드")
    print("  (conv_head Grad-CAM) 는 +0.114, 중간에 넣었던 conv_head Grad-CAM++ x")
    print("  blocks.4 곱 융합은 -0.043 이었다.")
    print()


def print_faith_comparison(current, baseline):
    print("=" * 74)
    print("  이전과 비교 (충실도)")
    print(f"    이전  {baseline['model_version']}  "
          f"CAM={', '.join(baseline.get('cam_layers', ['?']))}  "
          f"{baseline.get('sample_count')}장")
    print(f"    현재  {current['model_version']}  "
          f"CAM={', '.join(current.get('cam_layers', ['?']))}  "
          f"{current.get('sample_count')}장")
    print("=" * 74)
    if baseline.get("sample_count") != current.get("sample_count"):
        print("  ⚠ 표본 수가 다릅니다. 같은 목록이 아니면 비교는 의미가 없습니다.")
    print()
    for key, name, good in (("deletion", "deletion (낮을수록 좋음)", -1),
                            ("insertion", "insertion (높을수록 좋음)", 1),
                            ("score", "score (높을수록 좋음)", 1)):
        now, before = current.get(key), baseline.get(key)
        if now is None or before is None:
            print("  " + lj(name, 30) + rj("비교 불가", 22))
            continue
        diff = now - before
        mark = "개선" if diff * good > 0 else ("악화" if diff * good < 0 else "동일")
        print("  " + lj(name, 30)
              + f"{before:7.3f}  →{now:7.3f}   {diff:+.3f}  {mark}")
    print()


# =============================================
# 출력
# =============================================
def print_report(result):
    print("=" * 74)
    print("  CAM 로컬라이제이션 품질")
    print(f"    모델      {result['model_version']}")
    print(f"    CAM 레이어 {', '.join(result['cam_layers'])}")
    print(f"    표본      {result['sample_count']}장 (HAM10000 더모스코프, 마스크 보유분)")
    print("=" * 74)
    print()
    print("  " + lj("전체", 24) + rj("pointing", 10) + rj("energy", 10) + rj("IoU", 10))
    print("  " + "-" * 54)
    print("  " + lj("", 24) + pct(result["pointing_game"], 10)
          + pct(result["energy_ratio"], 10) + pct(result["iou"], 10))

    print()
    print("  병변 면적별 — 작은 병변에서 떨어지는지가 핵심 (폰카 사진의 대리 지표)")
    print("  " + lj("면적 비율", 24) + rj("장수", 6) + rj("pointing", 10)
          + rj("energy", 10) + rj("IoU", 10))
    print("  " + "-" * 60)
    for b in result["by_area"]:
        print("  " + lj(b["range"], 24) + rj(str(b["n"]), 6)
              + pct(b["pointing_game"], 10) + pct(b["energy_ratio"], 10)
              + pct(b["iou"], 10))

    print()
    print("  분류 정답/오답별 — 틀린 건의 CAM 이 더 흩어지는지")
    print("  " + lj("", 24) + rj("장수", 6) + rj("pointing", 10)
          + rj("energy", 10) + rj("IoU", 10))
    print("  " + "-" * 60)
    for key, name in (("correct", "분류 정답"), ("incorrect", "분류 오답")):
        b = result["by_correctness"][key]
        print("  " + lj(name, 24) + rj(str(b["n"]), 6)
              + pct(b["pointing_game"], 10) + pct(b["energy_ratio"], 10)
              + pct(b["iou"], 10))

    if "quality" in result:
        q = result["quality"]
        print()
        print("  히트맵 집중도 (서빙 응답의 heatmap_quality 와 같은 값)")
        print("    " + lj("표시 영역 비율", 36) + pct(q["focus_area"], 8))
        print("    " + lj("상위 10% 격자의 에너지 점유율", 36) + pct(q["peak_ratio"], 8))
        print("    두 값은 **서술값**이다. 집중도와 히트맵 신뢰도의 관계는 출처마다")
        print("    반대로 나와(--faithfulness 로 확인) 좋다/나쁘다 판정에 쓸 수 없다.")
    print()


def print_comparison(current, baseline):
    print("=" * 74)
    print("  이전과 비교")
    print(f"    이전  {baseline['model_version']}  "
          f"CAM={', '.join(baseline.get('cam_layers', ['?']))}  "
          f"{baseline.get('sample_count')}장")
    print(f"    현재  {current['model_version']}  "
          f"CAM={', '.join(current.get('cam_layers', ['?']))}  "
          f"{current.get('sample_count')}장")
    print("=" * 74)
    if baseline.get("sample_count") != current.get("sample_count"):
        print("  ⚠ 표본 수가 다릅니다. 같은 목록이 아니면 비교는 의미가 없습니다.")

    def delta(now, before, label):
        if now is None or before is None:
            print("  " + lj(label, 36) + rj("비교 불가", 22))
            return
        diff = (now - before) * 100
        sign = "+" if diff >= 0 else ""
        print("  " + lj(label, 36)
              + f"{before * 100:7.2f}  →{now * 100:7.2f}   {sign}{diff:.2f}%p")

    print("\n  전체 (높을수록 좋음)")
    for key, name in (("pointing_game", "  pointing game"),
                      ("energy_ratio", "  energy ratio"),
                      ("iou", "  IoU@0.5max")):
        delta(current.get(key), baseline.get(key), name)

    print("\n  병변 면적별 pointing game")
    now_bins = {b["range"]: b for b in current.get("by_area", [])}
    was_bins = {b["range"]: b for b in baseline.get("by_area", [])}
    for rng in sorted(set(now_bins) | set(was_bins)):
        delta(now_bins.get(rng, {}).get("pointing_game"),
              was_bins.get(rng, {}).get("pointing_game"), f"  {rng}")

    if "quality" in current and "quality" in baseline:
        print("\n  히트맵 집중도 (표시 영역 비율은 **낮을수록** 집중)")
        delta(current["quality"]["focus_area"], baseline["quality"]["focus_area"],
              "  표시 영역 비율")
        delta(current["quality"]["peak_ratio"], baseline["quality"]["peak_ratio"],
              "  상위 10% 에너지 점유율")
        if current["quality"].get("contrast") is not None:
            delta(current["quality"]["contrast"], baseline["quality"].get("contrast"),
                  "  원본 CAM 대비")
    print()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="CAM 이 실제로 병변을 짚는지 정답 마스크로 측정한다 "
                    "(HAM10000 더모스코프 전용 — docstring 의 한계 설명을 읽을 것)")
    parser.add_argument("--csv", type=Path, required=True,
                        help="홀드아웃 목록 (tests/baselines/holdout.csv)")
    parser.add_argument("--image-dir", type=Path, nargs="+", required=True)
    parser.add_argument("--mask-dir", type=Path, nargs="+",
                        help="HAM10000 병변 세그멘테이션 폴더 "
                             "(--faithfulness 모드에서는 필요 없다)")
    parser.add_argument("--faithfulness", action="store_true",
                        help="마스크 없이 deletion/insertion 충실도를 측정한다. "
                             "PAD-UFES(폰카)·SCIN(염증)처럼 공개 마스크가 없는 "
                             "출처에서 쓸 수 있는 유일한 정량 지표다")
    parser.add_argument("--id-col", default="image_id")
    # evaluate.py 의 기본값은 HAM10000 원본 기준의 "dx" 지만, 이 하네스는 holdout.csv
    # (image_id,label,src)를 쓰는 것이 정상 경로이므로 "label" 을 기본으로 둔다.
    parser.add_argument("--label-col", default="label",
                        help="기본 'label' — holdout.csv 기준 "
                             "(HAM10000 원본 metadata.csv 를 직접 쓰면 'dx')")
    parser.add_argument("--limit", type=int, help="앞 N 장만 (빨리 감 잡을 때)")
    parser.add_argument("--json", type=Path, help="결과를 이 경로에 JSON 으로 저장")
    parser.add_argument("--compare", type=Path, help="이전 --json 결과와 비교")
    parser.add_argument("--min-pointing-game", type=float,
                        help="pointing game 이 이 값 아래면 종료 코드 1 (CI 용)")
    parser.add_argument("--min-score", type=float,
                        help="충실도 score 가 이 값 아래면 종료 코드 1 (CI 용). "
                             "0 을 주면 '히트맵이 근거를 가리키기는 한다'를 보장한다")
    args = parser.parse_args()
    if not args.faithfulness and not args.mask_dir:
        parser.error("--mask-dir 이 필요합니다 (또는 --faithfulness 를 쓰세요)")

    loaded, missing, unknown = evaluate.load_from_csv(
        args.csv, args.image_dir, args.id_col, args.label_col)
    no_image = len(missing)
    if no_image:
        print(f"  ⚠ 이미지를 못 찾은 행 {no_image}개 (--image-dir 확인)")
    if unknown:
        print(f"  ⚠ 병명 목록에 없는 라벨로 제외된 행: {dict(unknown)} "
              f"(--label-col 이 맞는지 확인)")

    if args.faithfulness:
        print(f"  목록 {args.csv}: 이미지 {len(loaded)}장 "
              f"— 충실도 모드(마스크 불필요)")
        if not loaded:
            print("\n  측정할 표본이 없습니다. --image-dir 과 --label-col 을 확인하세요.")
            return 2
        result = evaluate_faithfulness(loaded, args.limit)
        print_faith_report(result)
    else:
        samples, no_mask = [], 0
        for image_path, label in loaded:
            mask_path = _resolve_mask(args.mask_dir, image_path.name)
            if mask_path is None:
                no_mask += 1
                continue
            samples.append((image_path, mask_path, label))
        print(f"  목록 {args.csv}: 이미지 {len(loaded)}장 확인, "
              f"그중 마스크 보유 {len(samples)}장")
        if no_mask:
            print(f"  ⚠ 마스크가 없어 제외한 이미지 {no_mask}장 "
                  f"— PAD/SCIN 에는 공개 마스크가 없으므로 정상입니다")
        if not samples:
            print("\n  측정할 표본이 없습니다. --mask-dir 경로와 파일명 규칙"
                  "(ISIC_xxxxxxx_segmentation.png)을 확인하세요.")
            return 2
        result = evaluate_cam(samples, args.limit)
        print_report(result)

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(result, indent=2, ensure_ascii=False),
                             encoding="utf-8")
        print(f"  저장: {args.json}\n")

    if args.compare:
        baseline = json.loads(args.compare.read_text(encoding="utf-8"))
        same = (baseline.get("mode") == "faithfulness") == bool(args.faithfulness)
        if not same:
            print("  ⚠ 기준선이 다른 모드로 측정됐습니다 (마스크 vs 충실도). 비교 생략.")
        elif args.faithfulness:
            print_faith_comparison(result, baseline)
        else:
            print_comparison(result, baseline)

    if args.min_pointing_game is not None:
        got = result.get("pointing_game")
        if got is None or got < args.min_pointing_game:
            print(f"  ✗ pointing game {got} < 요구치 {args.min_pointing_game}")
            return 1
    if args.min_score is not None:
        got = result.get("score")
        if got is None or got < args.min_score:
            print(f"  ✗ 충실도 score {got} < 요구치 {args.min_score}")
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
