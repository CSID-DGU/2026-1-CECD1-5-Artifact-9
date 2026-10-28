"""
확대 보기(crops)와 확대 재판정이 약속대로 움직이는지 — 합성 이미지만으로 확인한다.

왜 필요한가
----------
확대 보기는 실패해도 분석 결과가 나가도록 짜여 있다(run_inference 의 try/except). 그래서
고장이 나도 **화면에는 아무 일도 없어 보인다** — 확대 히트맵이 조용히 사라지거나, 재판정으로
바뀐 병명 옆에 옛 병명의 히트맵이 붙어 나가도 확률·병명은 그럴듯하다. 사람 눈으로는 못 잡으니
기계가 매번 확인한다.

여기서 보는 것:
  [0] 확대 영역 고르기 규칙 — 겹침 판정, 2번째부터의 봉우리 기준, 상자 크기
  [1] 응답 모양 — crops 의 키, box_norm 범위, 최대 개수, 상자끼리 겹치지 않음
  [2] 재판정 — 신뢰도가 낮을 때만 하고, 하면 원본 1순위를 남기고 확신 낮음 등급을 유지한다
  [3] 재판정으로 1순위가 바뀌면 히트맵도 새 1순위를 설명한다
  [4] 재추론이 터져도 분석은 원본 그대로 나가고, 확대 보기는 잘라 키운 히트맵으로 대신한다
  [5] 안면부는 확대 영역을 다시 판정하지 않는다
  [6] HEATMAP_CROPS=0 이면 확대 보기를 만들지 않는다

실행
----
    docker compose exec -T fastapi python tests/test_heatmap_crops.py
    (CI 에서는 .github/workflows/ci.yml 의 fastapi 잡이 매번 돌린다)
"""

import base64
import io
import os
import sys
from pathlib import Path

FASTAPI_DIR = Path(__file__).resolve().parent.parent
os.chdir(FASTAPI_DIR)
sys.path.insert(0, str(FASTAPI_DIR))

# test_model_contract.py 와 같은 이유 — 이 스크립트는 포트를 열지 않는다.
os.environ.setdefault("INTERNAL_API_SECRET", "test_heatmap_crops.py-does-not-serve")

import torch  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

import main as serving  # noqa: E402

# 한 스레드로 돈다. 역전파(CAM)를 여러 스레드로 돌리면 합산 순서가 실행마다 달라 값이 1e-7 수준으로
# 흔들린다(서버에는 영향이 없다 — test_concurrent_heatmap.py 독스트링 참고). 여기서는 같은 사진의 두
# 응답을 바이트 단위로 비교해야 하므로 그 흔들림을 없앤다. 1스레드에서는 비트 단위로 같다.
torch.set_num_threads(1)

failures: list[str] = []
skipped: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  ✅ {name}")
    else:
        print(f"  ❌ {name}")
        if detail:
            print(f"       {detail}")
        failures.append(name)


def skip(name: str, why: str) -> None:
    print(f"  ⏭  {name} — {why}")
    skipped.append(name)


def make_image(seed: int, size=(480, 360)) -> bytes:
    """얼룩 몇 개가 흩어진 피부색 사진. 히트맵 봉우리가 여러 곳에 생기도록 한다."""
    img = Image.new("RGB", size, (214, 168, 140))
    draw = ImageDraw.Draw(img)
    spots = [(0.25, 0.3, 46), (0.7, 0.62, 38), (0.5, 0.82, 24), (0.82, 0.2, 18)]
    for i, (fx, fy, r) in enumerate(spots):
        cx, cy = fx * size[0] + seed * 9, fy * size[1] - seed * 7
        draw.ellipse([cx - r, cy - r, cx + r, cy + r],
                     fill=(120 - i * 15 + seed * 10, 70 + seed * 12, 60 + i * 10))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def overlaps(a, b) -> bool:
    """main._boxes_overlap 과 같은 규칙을 따로 적는다 — 서버 함수로 서버 함수를 검사하면 규칙이
    바뀌어도 늘 통과한다. IoU 30% 이상이거나, 작은 상자의 60% 이상이 다른 상자 안에 들어가면 겹친 것이다."""
    iw = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    ih = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = iw * ih
    area_a, area_b = (a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter) >= 0.3 or inter >= 0.6 * min(area_a, area_b)


IMAGES = [make_image(s) for s in range(3)]
ORIGINAL_THRESHOLD = serving.LOW_CONFIDENCE_THRESHOLD


# =============================================
print("[0] 확대 영역 고르기 규칙")

for name, a, b, want in [
    ("떨어진 두 상자", (0, 0, 10, 10), (20, 20, 30, 30), False),
    ("IoU 35%", (0, 0, 10, 10), (0, 3.5, 10, 13.5), True),
    ("큰 상자 안의 작은 상자(IoU 는 4%)", (0, 0, 50, 50), (10, 10, 20, 20), True),
    ("작은 쪽의 절반만 걸침", (0, 0, 50, 50), (45, 10, 55, 20), False),
]:
    check(f"{name} → {'겹침' if want else '안 겹침'}",
          serving._boxes_overlap(a, b) == want == overlaps(a, b),
          f"서버 {serving._boxes_overlap(a, b)} / 규칙 {overlaps(a, b)}")



def two_blobs(second_peak: float) -> torch.Tensor:
    """왼쪽 위에 봉우리 1.0, 오른쪽 아래에 second_peak 인 표시용 맵 (1,1,192,256)."""
    ys, xs = torch.meshgrid(torch.arange(192.0), torch.arange(256.0), indexing="ij")
    blob = lambda cx, cy: torch.exp(-((xs - cx) ** 2 + (ys - cy) ** 2) / (2 * 12.0 ** 2))  # noqa: E731
    return (blob(60, 50) + second_peak * blob(200, 150))[None, None]


# 2번째 영역의 기준(_CROP_MIN_PEAK)을 응답만 보고는 확인할 수 없다 — 합성 사진에 마침 그 사이의
# 봉우리가 없으면 기준이 사라져도 통과한다. 그래서 맵을 직접 만들어 넣는다.
below = serving._propose_crops(two_blobs(serving._CROP_MIN_PEAK - 0.1), 1024, 768, 3)
above = serving._propose_crops(two_blobs(serving._CROP_MIN_PEAK + 0.1), 1024, 768, 3)
check(f"두 번째 봉우리가 {serving._CROP_MIN_PEAK} 미만이면 확대 영역은 하나다", len(below) == 1,
      f"{[round(b[4], 2) for b in below]}")
check(f"두 번째 봉우리가 {serving._CROP_MIN_PEAK} 이상이면 둘이다", len(above) == 2,
      f"{[round(b[4], 2) for b in above]}")
side = above[0][2] - above[0][0]
check("확대 영역은 정사각형이고 짧은 변의 22~60% 다",
      all(abs((b[2] - b[0]) - (b[3] - b[1])) < 1e-6 for b in above)
      and 0.22 * 768 - 1e-6 <= side <= 0.60 * 768 + 1e-6, f"{above}")

# =============================================
print("\n[1] 응답 모양")

for idx, data in enumerate(IMAGES):
    res = serving.run_inference(data, "skin_lesion")
    crops = res.get("crops")
    check(f"이미지 {idx}: crops 가 리스트이고 최대 HEATMAP_CROPS({serving.HEATMAP_CROPS})개다",
          isinstance(crops, list) and len(crops) <= serving.HEATMAP_CROPS,
          f"crops={type(crops).__name__} {len(crops) if isinstance(crops, list) else ''}")
    check(f"이미지 {idx}: heatmap_style 이 서버 상수와 같다",
          res.get("heatmap_style") == serving.HEATMAP_STYLE, f"{res.get('heatmap_style')!r}")
    if not crops:
        skip(f"이미지 {idx}: 확대 영역 검사", "히트맵이 비어 확대 영역이 없음")
        continue
    keys_ok = all(set(c) == {"rank", "box_norm", "peak", "heatmap_base64"} for c in crops)
    check(f"이미지 {idx}: 확대 영역의 키가 rank/box_norm/peak/heatmap_base64 다",
          keys_ok, f"{[sorted(c) for c in crops]}")
    check(f"이미지 {idx}: rank 가 1부터 빈틈없이 매겨진다",
          [c["rank"] for c in crops] == list(range(1, len(crops) + 1)))
    boxes = [c["box_norm"] for c in crops]
    check(f"이미지 {idx}: box_norm 이 0~1 안의 [x0,y0,x1,y1] 이다",
          all(len(b) == 4 and 0 <= b[0] < b[2] <= 1 and 0 <= b[1] < b[3] <= 1 for b in boxes),
          f"{boxes}")
    check(f"이미지 {idx}: 확대 영역끼리 겹치지 않는다(IoU 30% 또는 작은 쪽 60%)",
          not any(overlaps(a, b) for i, a in enumerate(boxes) for b in boxes[i + 1:]),
          f"{boxes}")
    decoded = [Image.open(io.BytesIO(base64.b64decode(c["heatmap_base64"]))) for c in crops]
    check(f"이미지 {idx}: 확대 히트맵이 JPEG 이고 긴 변이 {serving._CROP_MIN_SIDE}~{serving._CROP_MAX_SIDE} 이다",
          all(d.format == "JPEG" and serving._CROP_MIN_SIDE <= max(d.size) <= serving._CROP_MAX_SIDE
              for d in decoded),
          f"{[(d.format, d.size) for d in decoded]}")
    check(f"이미지 {idx}: 1번 영역 봉우리가 0.5 이상, 2번부터는 _CROP_MIN_PEAK 이상이다",
          crops[0]["peak"] >= 0.5 and all(c["peak"] >= serving._CROP_MIN_PEAK for c in crops[1:]),
          f"{[c['peak'] for c in crops]}")

# =============================================
print("\n[2] 확대 재판정은 신뢰도가 낮을 때만")

try:
    serving.LOW_CONFIDENCE_THRESHOLD = 0.0          # 무엇도 낮지 않다
    never = serving.run_inference(IMAGES[0], "skin_lesion")
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01         # 무엇이든 낮다
    always = serving.run_inference(IMAGES[0], "skin_lesion")
finally:
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD

check("신뢰도가 높으면 재판정하지 않는다(full_image_top1 = null)",
      never["full_image_top1"] is None and never["confidence_level"] == serving.CONFIDENCE_NORMAL,
      f"full_image_top1={never['full_image_top1']} level={never['confidence_level']}")
if always["crops"]:
    check("신뢰도가 낮으면 재판정하고 원본 1순위를 full_image_top1 에 남긴다",
          always["full_image_top1"] is not None
          and always["full_image_top1"]["disease_code"] == never["top1"]["disease_code"]
          and always["full_image_top1"]["confidence"] == never["top1"]["confidence"],
          f"full_image_top1={always['full_image_top1']} 원본 top1={never['top1']}")
    check("재판정해도 확신 낮음 등급은 원본 기준으로 low 그대로다",
          always["confidence_level"] == serving.CONFIDENCE_LOW, always["confidence_level"])
    total = sum(item["confidence"] for item in always["top5"])
    check("재판정한 top5 도 확률이다(내림차순, 합 ≤ 1)",
          all(a["confidence"] >= b["confidence"] for a, b in zip(always["top5"], always["top5"][1:]))
          and total <= 1.0 + 1e-3,
          f"{[i['confidence'] for i in always['top5']]}")
else:
    skip("재판정 검사", "확대 영역이 없어 재판정할 것이 없음")

# =============================================
print("\n[3] 재판정으로 1순위가 바뀌면 히트맵도 새 1순위를 설명한다")

# 확대 영역의 logits 만 한 클래스로 크게 기울여 재판정이 반드시 그 클래스로 가게 한다.
# activation 과의 연결은 그대로라(logits + 상수) 확대 영역 CAM 도 정상 경로로 계산된다.
base = serving.run_inference(IMAGES[1], "skin_lesion")
classes = serving.TASKS["skin_lesion"]["classes"]
target = next(i for i, c in enumerate(classes) if c != base["top1"]["disease_code"])
real_crop_forward = serving._crop_forward


def biased_crop_forward(crops, tm):
    biased = []
    for logits, act in real_crop_forward(crops, tm):
        bias = torch.zeros_like(logits)
        bias[:, target] = 1e3
        biased.append((logits + bias, act))
    return biased


try:
    serving._crop_forward = biased_crop_forward
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01
    flipped = serving.run_inference(IMAGES[1], "skin_lesion")
finally:
    serving._crop_forward = real_crop_forward
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD

if base["crops"]:
    check("재판정 결과가 확대 영역 쪽 클래스로 바뀌었다",
          flipped["top1"]["disease_code"] == classes[target]
          and flipped["full_image_top1"]["disease_code"] == base["top1"]["disease_code"],
          f"top1={flipped['top1']['disease_code']} full={flipped['full_image_top1']}")
    # 같은 사진·같은 결정적 모델에서 설명하는 클래스가 달라졌으니 그림도 달라야 한다.
    check("전체 히트맵이 옛 1순위의 것이 아니다(새 1순위로 다시 그렸다)",
          flipped["heatmap_base64"] is not None and flipped["heatmap_base64"] != base["heatmap_base64"])
    # 기대값: 새 1순위의 CAM 에서 고른 확대 영역. 옛 1순위의 영역을 그대로 두면 여기서 걸린다.
    tm = serving.TASK_MODELS["skin_lesion"]
    image = Image.open(io.BytesIO(IMAGES[1])).convert("RGB")
    with tm.lock:
        _, target_cam, _ = serving._compute_gradcam(tm.transform(image).unsqueeze(0), tm, target=target)
    _, target_boxes, _ = serving._crop_regions(target_cam, image)
    w, h = image.size
    expected = [[round(b[0] / w, 4), round(b[1] / h, 4), round(b[2] / w, 4), round(b[3] / h, 4)]
                for b in target_boxes]
    check("확대 영역도 새 1순위의 히트맵에서 다시 골랐다",
          [c["box_norm"] for c in flipped["crops"]] == expected,
          f"응답 {[c['box_norm'] for c in flipped['crops']]} / 기대 {expected}")
else:
    skip("1순위 변경 검사", "확대 영역이 없음")

# 확신 낮음 등급은 섞은 확률이 아니라 원본 신뢰도로 매긴다. 원본 1순위 쪽으로 확대 영역을 기울이면
# 섞은 신뢰도는 원본보다 올라간다 — 경고선을 원본 바로 위에 두면 섞은 쪽은 선을 넘는다.
same_class = classes.index(base["top1"]["disease_code"])
original_conf = base["top1"]["confidence"]


def toward_original(crops, tm):
    return [(logits + torch.nn.functional.one_hot(torch.tensor([same_class]), len(classes)) * 1e3, act)
            for logits, act in real_crop_forward(crops, tm)]


try:
    serving._crop_forward = toward_original
    serving.LOW_CONFIDENCE_THRESHOLD = original_conf + 1e-3
    raised = serving.run_inference(IMAGES[1], "skin_lesion")
finally:
    serving._crop_forward = real_crop_forward
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD

if base["crops"]:
    check("재판정으로 신뢰도가 경고선을 넘어도 확신 낮음 등급은 원본 기준(low)이다",
          raised["top1"]["confidence"] > original_conf + 1e-3
          and raised["confidence_level"] == serving.CONFIDENCE_LOW,
          f"섞은 신뢰도 {raised['top1']['confidence']} / 경고선 {original_conf + 1e-3:.4f} / "
          f"등급 {raised['confidence_level']}")

# =============================================
print("\n[4] 재추론이 터져도 분석은 나간다")


def boom(*_args, **_kwargs):
    raise RuntimeError("의도된 실패")


real_cams = serving._crop_layercams
try:
    serving._crop_layercams = boom
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01
    broken = serving.run_inference(IMAGES[0], "skin_lesion")
finally:
    serving._crop_layercams = real_cams
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD

check("재추론 실패 시 원본 판정이 그대로 나간다(재판정 없음)",
      broken["top1"] == never["top1"] and broken["full_image_top1"] is None,
      f"top1={broken['top1']} full={broken['full_image_top1']}")
check("재추론 실패 시에도 전체 히트맵과 같은 곳의 확대 보기가 나간다",
      broken["heatmap_base64"] == never["heatmap_base64"]
      and [c["box_norm"] for c in broken["crops"]] == [c["box_norm"] for c in never["crops"]],
      f"{[c['box_norm'] for c in broken['crops']]} vs {[c['box_norm'] for c in never['crops']]}")

# =============================================
print("\n[5] 안면부는 다시 판정하지 않는다")

if "facial" in serving.TASK_MODELS:
    try:
        serving.LOW_CONFIDENCE_THRESHOLD = 1.01
        facial = serving.run_inference(IMAGES[2], "facial")
    finally:
        serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
    check("안면부: 신뢰도가 낮아도 full_image_top1 = null (재판정 안 함)",
          facial["full_image_top1"] is None, f"{facial['full_image_top1']}")
    check("안면부: 확대 보기는 만든다",
          isinstance(facial["crops"], list), f"{type(facial['crops']).__name__}")
else:
    skip("안면부 검사", f"미로드 — {serving.TASK_LOAD_ERRORS.get('facial', '사유 불명')}")

# =============================================
print("\n[6] HEATMAP_CROPS=0")

original_crops = serving.HEATMAP_CROPS
try:
    serving.HEATMAP_CROPS = 0
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01
    none = serving.run_inference(IMAGES[0], "skin_lesion")
finally:
    serving.HEATMAP_CROPS = original_crops
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
check("확대 보기를 끄면 crops 가 비고 재판정도 하지 않는다",
      none["crops"] == [] and none["full_image_top1"] is None and none["heatmap_base64"] is not None,
      f"crops={len(none['crops'])} full={none['full_image_top1']}")

# =============================================
print()
if failures:
    print(f"❌ 실패 {len(failures)}건: {', '.join(failures)}")
    sys.exit(1)
print(f"✅ 모두 통과 (건너뜀 {len(skipped)}건)")
