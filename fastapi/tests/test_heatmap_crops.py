"""
확대 보기(crops)와 확대 재판정이 약속대로 움직이는지 — 합성 이미지만으로 확인한다.

왜 필요한가
----------
확대 보기는 실패해도 분석 결과가 나가도록 짜여 있다(run_inference 의 try/except). 그래서
고장이 나도 **화면에는 아무 일도 없어 보인다** — 확대 히트맵이 조용히 사라지거나, 재판정으로
바뀐 병명 옆에 옛 병명의 히트맵이 붙어 나가도 확률·병명은 그럴듯하다. 사람 눈으로는 못 잡으니
기계가 매번 확인한다. 같은 이유로 **"확대 영역이 없어서 건너뜀"을 통과로 치지 않는다** — CAM 이
살아 있으면 합성 사진에도 늘 1곳 이상 생기므로(1번 영역은 봉우리 0.5 이상이면 되고, 표시용 맵의
최댓값은 1 이다), 비어 있다는 것 자체가 고장이다.

여기서 보는 것:
  [0] 확대 영역 고르기 규칙 — 겹침 판정(IoU·포함 각각), 2번째부터의 봉우리 기준, 상자 크기
  [1] 응답 모양 — crops 의 키, box_norm 범위, 최대 개수, 상자끼리 겹치지 않음, source
  [2] 재판정 — 신뢰도가 낮을 때만 하고, 원본과 확대 영역 평균을 반반 섞으며, 원본 1순위를 남기고
      확신 낮음 등급을 유지한다
  [3] 재판정으로 1순위가 바뀌면 전체 히트맵·확대 영역·확대 히트맵이 모두 새 1순위를 설명한다
  [4] 재추론이나 새 1순위의 CAM 이 터져도 분석은 원본 그대로 나가고, 확대 보기는 잘라 키운 그림이다
  [5] 안면부는 확대 영역을 다시 판정하지 않고 잘라 키운 확대 보기만 만든다
  [6] HEATMAP_CROPS 는 보여 주는 수만 바꾼다 — 0 이어도 재판정은 그대로다
  [7] HEATMAP_SMOOTH 는 색만 바꾼다 — 확대 영역과 재판정은 그대로다
  [8] 요청의 crops=False 는 확대 보기와 재판정을 끈다
  [9] 1순위가 바뀌는 경로를 여러 스레드가 동시에 타도 결과가 한 줄로 돌릴 때와 같다

실행
----
    docker compose exec -T fastapi python tests/test_heatmap_crops.py
    (CI 에서는 .github/workflows/ci.yml 의 fastapi 잡이 매번 돌린다)
"""

import base64
import io
import os
import sys
import threading
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

# 확대 영역 수를 서버 기본값으로 못 박는다. `docker compose exec` 로 돌리면 컨테이너의 환경변수를 그대로
# 읽는데, 거기서 확대 보기를 꺼 두었다면 아래 검사가 전부 "확대 영역 없음"으로 실패해 원인을 헷갈리게 한다.
serving.HEATMAP_CROPS = 3

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


def norm_boxes(boxes, image: Image.Image) -> list[list[float]]:
    """run_inference 가 box_norm 을 만드는 것과 같은 반올림."""
    w, h = image.size
    return [[round(b[0] / w, 4), round(b[1] / h, 4), round(b[2] / w, 4), round(b[3] / h, 4)] for b in boxes]


def crop_render(crop: Image.Image, cam: torch.Tensor) -> str | None:
    """확대 히트맵 하나를 서버와 같은 순서로 그린다 — [3] 의 기대값."""
    disp = serving._display_map(cam, *crop.size, smooth=serving._CROP_SMOOTH)
    return serving._render_overlay(crop, disp, serving._CROP_MIN_SIDE, serving._CROP_MAX_SIDE, "기대값")


IMAGES = [make_image(s) for s in range(3)]
ORIGINAL_THRESHOLD = serving.LOW_CONFIDENCE_THRESHOLD
CLASSES = serving.TASKS["skin_lesion"]["classes"]
TM = serving.TASK_MODELS["skin_lesion"]


def tensor_of(image: Image.Image) -> torch.Tensor:
    return TM.transform(image).unsqueeze(0).to(serving.device)


# =============================================
print("[0] 확대 영역 고르기 규칙")

for name, a, b, want in [
    ("떨어진 두 상자", (0, 0, 10, 10), (20, 20, 30, 30), False),
    # 같은 크기 상자를 위아래로 어긋나게 겹친다. 포함 비율이 60% 를 못 넘어 IoU 규칙만이 가른다.
    ("IoU 35% · 작은 쪽의 52% 포함 (IoU 규칙으로 겹침)", (0, 0, 10, 10), (0, 4.815, 10, 14.815), True),
    ("IoU 29% · 작은 쪽의 45% 포함", (0, 0, 10, 10), (0, 5.5, 10, 15.5), False),
    ("IoU 48% · 작은 쪽의 65% 포함", (0, 0, 10, 10), (0, 3.5, 10, 13.5), True),
    ("큰 상자 안의 작은 상자(IoU 는 4%, 포함 규칙으로 겹침)", (0, 0, 50, 50), (10, 10, 20, 20), True),
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
    check(f"이미지 {idx}: crops 가 리스트이고 1~HEATMAP_CROPS({serving.HEATMAP_CROPS})개다",
          isinstance(crops, list) and 1 <= len(crops) <= serving.HEATMAP_CROPS,
          f"crops={type(crops).__name__} {len(crops) if isinstance(crops, list) else ''} "
          f"heatmap={'있음' if res.get('heatmap_base64') else '없음'}")
    check(f"이미지 {idx}: heatmap_style 이 서버 상수와 같다",
          res.get("heatmap_style") == serving.HEATMAP_STYLE, f"{res.get('heatmap_style')!r}")
    if not crops:
        continue  # 위에서 이미 실패로 셌다. 아래는 확대 영역이 있어야 볼 수 있는 것들이다.
    keys_ok = all(set(c) == {"rank", "box_norm", "peak", "heatmap_base64", "source"} for c in crops)
    check(f"이미지 {idx}: 확대 영역의 키가 rank/box_norm/peak/heatmap_base64/source 다",
          keys_ok, f"{[sorted(c) for c in crops]}")
    check(f"이미지 {idx}: 피부 병변은 확대 영역을 다시 넣어 그린다(source=reinfer)",
          all(c.get("source") == "reinfer" for c in crops), f"{[c.get('source') for c in crops]}")
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
print("\n[2] 확대 재판정은 신뢰도가 낮을 때만, 원본과 반반")

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
check("신뢰도가 낮아도 확대 영역이 있다(재판정할 재료)", bool(always["crops"]), f"crops={len(always['crops'])}")
check("신뢰도가 낮으면 재판정하고 원본 1순위를 full_image_top1 에 남긴다",
      always["full_image_top1"] is not None
      and always["full_image_top1"]["disease_code"] == never["top1"]["disease_code"]
      and always["full_image_top1"]["confidence"] == never["top1"]["confidence"],
      f"full_image_top1={always['full_image_top1']} 원본 top1={never['top1']}")
check("재판정해도 확신 낮음 등급은 원본 기준으로 low 그대로다",
      always["confidence_level"] == serving.CONFIDENCE_LOW, always["confidence_level"])

# 공식을 그대로 다시 계산해 맞춘다: 0.5 × 원본 확률 + 0.5 × 확대 영역 확률의 평균.
# 확대 영역만으로 정하거나 로짓을 섞거나 비율을 바꾸면 여기서 걸린다 — 기록된 재판정 수치
# (baselines/README.md ③)가 이 공식으로 잰 것이다.
image0 = Image.open(io.BytesIO(IMAGES[0])).convert("RGB")
with TM.lock:
    logits0, cam0, _ = serving._compute_gradcam(tensor_of(image0), TM)
    _, _, crops0 = serving._crop_regions(cam0, image0)
    crop_probs0 = torch.cat([torch.softmax(out.detach(), dim=1) for out, _ in serving._crop_forward(crops0, TM)])
mixed0 = 0.5 * torch.softmax(logits0, dim=1)[0] + 0.5 * crop_probs0.mean(dim=0)
want_top5 = [(CLASSES[int(i)], round(float(mixed0[i]), 4)) for i in torch.topk(mixed0, 5).indices]
got_top5 = [(item["disease_code"], item["confidence"]) for item in always["top5"]]
check("재판정 확률은 원본과 확대 영역 평균을 반반 섞은 값이다",
      [c for c, _ in got_top5] == [c for c, _ in want_top5]
      and all(abs(g - w) <= 1.5e-4 for (_, g), (_, w) in zip(got_top5, want_top5)),
      f"응답 {got_top5} / 기대 {want_top5}")

# =============================================
print("\n[3] 재판정으로 1순위가 바뀌면 히트맵도 새 1순위를 설명한다")

# 기준은 재판정하지 않은 원본이어야 한다 — 서버 기본 경고선으로 돌리면 이 사진이 이미 재판정됐을 수 있다.
try:
    serving.LOW_CONFIDENCE_THRESHOLD = 0.0
    base = serving.run_inference(IMAGES[1], "skin_lesion")
finally:
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
original_code = base["top1"]["disease_code"]
original_conf = base["top1"]["confidence"]
pred = CLASSES.index(original_code)
check("전제: 합성 사진의 원본 신뢰도가 0.99 미만이다(그래야 확대 영역 쪽으로 판정을 뒤집을 수 있다)",
      original_conf < 0.99, f"{original_code} {original_conf}")

# 바꿔 넣을 클래스는 **확대 영역이 원본과 달라지는** 것으로 고른다. 같은 곳을 짚는 클래스를 고르면
# "옛 영역을 그대로 둔" 고장이 아래 검사를 통과해 버린다.
image1 = Image.open(io.BytesIO(IMAGES[1])).convert("RGB")
base_boxes = [c["box_norm"] for c in base["crops"]]
target, expected, expected_crops = None, None, None
for k, code in enumerate(CLASSES):
    if code == original_code:
        continue
    with TM.lock:
        _, cam_k, _ = serving._compute_gradcam(tensor_of(image1), TM, target=k)
    _, boxes_k, crops_k = serving._crop_regions(cam_k, image1)
    if boxes_k and norm_boxes(boxes_k, image1) != base_boxes:
        target, expected, expected_crops = k, norm_boxes(boxes_k, image1), crops_k
        break
check("전제: 원본과 다른 곳을 짚는 클래스가 있다(없으면 아래 검사가 무의미하다)", target is not None,
      f"원본 영역 {base_boxes}")

real_crop_forward = serving._crop_forward


def biased_crop_forward(crops, tm):
    """확대 영역의 판정을 target 쪽으로 확실히(≈99.97%) 기울이되 포화시키지는 않는다. 1e3 처럼 키우면 softmax 가
    정확히 one-hot 이 되어 log_softmax 의 gradient(δ − p)가 0 이 되고, 확대 히트맵이 통째로 0 이라 비교할 것이 없다.
    activation 과의 연결은 그대로라(logits + 상수) 확대 영역 CAM 도 정상 경로로 계산된다."""
    biased = []
    for logits, act in real_crop_forward(crops, tm):
        bias = torch.zeros_like(logits)
        gap = logits.detach()
        bias[:, target] = float(gap.max() - gap[0, target]) + 10.0
        biased.append((logits + bias, act))
    return biased


if target is not None:
    try:
        serving._crop_forward = biased_crop_forward
        serving.LOW_CONFIDENCE_THRESHOLD = 1.01
        flipped = serving.run_inference(IMAGES[1], "skin_lesion")
    finally:
        serving._crop_forward = real_crop_forward
        serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD

    check("재판정 결과가 확대 영역 쪽 클래스로 바뀌었다",
          flipped["top1"]["disease_code"] == CLASSES[target]
          and (flipped["full_image_top1"] or {}).get("disease_code") == original_code,
          f"top1={flipped['top1']['disease_code']} full={flipped['full_image_top1']}")
    # 같은 사진·같은 결정적 모델에서 설명하는 클래스가 달라졌으니 그림도 달라야 한다.
    check("전체 히트맵이 옛 1순위의 것이 아니다(새 1순위로 다시 그렸다)",
          flipped["heatmap_base64"] is not None and flipped["heatmap_base64"] != base["heatmap_base64"])
    check("확대 영역도 새 1순위의 히트맵에서 다시 골랐다",
          [c["box_norm"] for c in flipped["crops"]] == expected,
          f"응답 {[c['box_norm'] for c in flipped['crops']]} / 기대 {expected}")
    # 확대 히트맵이 새 1순위를 설명하는지 — 같은 확대 영역을 새 1순위와 옛 1순위로 각각 그려 맞춰 본다.
    with TM.lock:
        new_cams = serving._crop_layercams(biased_crop_forward(expected_crops, TM), target)
        old_cams = serving._crop_layercams(biased_crop_forward(expected_crops, TM), pred)
    want_new = [crop_render(c, m) for c, m in zip(expected_crops, new_cams)]
    want_old = [crop_render(c, m) for c, m in zip(expected_crops, old_cams)]
    got = [c["heatmap_base64"] for c in flipped["crops"]]
    check("확대 히트맵이 새 1순위로 그린 것이다",
          got == want_new and got != want_old and all(c["source"] == "reinfer" for c in flipped["crops"]),
          f"새 1순위와 같음 {got == want_new} / 옛 1순위와 같음 {got == want_old}")
    check("새 1순위로 바뀌어도 확신 낮음 등급은 원본 기준이다",
          flipped["confidence_level"] == serving.CONFIDENCE_LOW, flipped["confidence_level"])
else:
    skip("1순위 변경 검사", "전제 실패 — 위에서 이미 실패로 셌다")


# 확신 낮음 등급은 섞은 확률이 아니라 원본 신뢰도로 매긴다. 원본 1순위 쪽으로 확대 영역을 기울이면
# 섞은 신뢰도는 0.5 × 원본 + 0.5 로 올라간다 — 경고선을 원본 바로 위에 두면 섞은 쪽은 선을 넘는다.
def toward_original(crops, tm):
    return [(logits + torch.nn.functional.one_hot(torch.tensor([pred]), len(CLASSES)) * 1e3, act)
            for logits, act in real_crop_forward(crops, tm)]


try:
    serving._crop_forward = toward_original
    serving.LOW_CONFIDENCE_THRESHOLD = original_conf + 1e-3
    raised = serving.run_inference(IMAGES[1], "skin_lesion")
finally:
    serving._crop_forward = real_crop_forward
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD

check("확대 영역이 원본 1순위만 가리키면 섞은 신뢰도는 0.5 × 원본 + 0.5 다",
      abs(raised["top1"]["confidence"] - (0.5 * original_conf + 0.5)) <= 1e-4,
      f"응답 {raised['top1']['confidence']} / 기대 {0.5 * original_conf + 0.5:.4f}")
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
check("재추론 실패 시에도 전체 히트맵과 같은 곳의 확대 보기가 잘라 키운 그림으로 나간다",
      bool(never["crops"])
      and broken["heatmap_base64"] == never["heatmap_base64"]
      and [c["box_norm"] for c in broken["crops"]] == [c["box_norm"] for c in never["crops"]]
      and all(c["source"] == "zoom" for c in broken["crops"]),
      f"{[(c['box_norm'], c['source']) for c in broken['crops']]} vs {[c['box_norm'] for c in never['crops']]}")

# 1순위가 바뀌는 경로에서 새 1순위의 CAM 이 실패하는 경우. _compute_gradcam 은 실패를 삼키고 None 을 주므로,
# 그대로 바꿔 끼우면 새 병명이 히트맵도 확대 보기도 없이 나간다 — 원본 판정과 원본 히트맵이 짝으로 남아야 한다.
real_gradcam = serving._compute_gradcam


def gradcam_fails_for_new_class(tensor, tm, target=None):
    logits, cam, quality = real_gradcam(tensor, tm, target)
    return (logits, None, None) if target is not None else (logits, cam, quality)


if target is not None:
    try:
        serving._compute_gradcam = gradcam_fails_for_new_class
        serving._crop_forward = biased_crop_forward
        serving.LOW_CONFIDENCE_THRESHOLD = 1.01
        cam_broken = serving.run_inference(IMAGES[1], "skin_lesion")
    finally:
        serving._compute_gradcam = real_gradcam
        serving._crop_forward = real_crop_forward
        serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
    check("새 1순위의 CAM 이 실패하면 원본 판정·원본 히트맵·잘라 키운 확대 보기로 나간다",
          cam_broken["top1"]["disease_code"] == original_code
          and cam_broken["full_image_top1"] is None
          and cam_broken["heatmap_base64"] == base["heatmap_base64"]
          and [c["box_norm"] for c in cam_broken["crops"]] == base_boxes
          and all(c["source"] == "zoom" for c in cam_broken["crops"]),
          f"top1={cam_broken['top1']['disease_code']} full={cam_broken['full_image_top1']} "
          f"heatmap={'같음' if cam_broken['heatmap_base64'] == base['heatmap_base64'] else '다름'} "
          f"crops={[(c['box_norm'], c['source']) for c in cam_broken['crops']]}")
else:
    skip("새 1순위 CAM 실패 검사", "전제 실패 — 위에서 이미 실패로 셌다")

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
    facial_jpegs = [Image.open(io.BytesIO(base64.b64decode(c["heatmap_base64"]))).format
                    for c in facial["crops"]]
    check("안면부: 확대 보기는 만들고, 잘라 키운 그림(source=zoom)이다",
          len(facial["crops"]) >= 1 and all(f == "JPEG" for f in facial_jpegs)
          and all(c["source"] == "zoom" for c in facial["crops"]),
          f"crops={len(facial['crops'])} {facial_jpegs} {[c.get('source') for c in facial['crops']]}")
else:
    skip("안면부 검사", f"미로드 — {serving.TASK_LOAD_ERRORS.get('facial', '사유 불명')}")

# =============================================
print("\n[6] HEATMAP_CROPS 는 보여 주는 수만 바꾼다")

# 화면을 덜 어지럽히려고 줄였는데 진단이 따라 바뀌면 안 된다 — 재판정은 늘 최대 _REJUDGE_CROPS 곳으로 한다.
try:
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01
    serving.HEATMAP_CROPS = 0
    none = serving.run_inference(IMAGES[0], "skin_lesion")
    serving.HEATMAP_CROPS = 1
    one = serving.run_inference(IMAGES[0], "skin_lesion")
finally:
    serving.HEATMAP_CROPS = 3
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
check("HEATMAP_CROPS=0 이면 확대 보기만 비고, 재판정 결과·전체 히트맵은 3 일 때와 같다",
      none["crops"] == [] and none["top5"] == always["top5"]
      and none["full_image_top1"] == always["full_image_top1"] and none["heatmap_base64"] == always["heatmap_base64"],
      f"crops={len(none['crops'])} top5 같음={none['top5'] == always['top5']} full={none['full_image_top1']}")
check("HEATMAP_CROPS=1 이면 1번 확대 보기만 나가고 재판정 결과는 3 일 때와 같다",
      [(c["box_norm"], c["heatmap_base64"]) for c in one["crops"]]
      == [(c["box_norm"], c["heatmap_base64"]) for c in always["crops"][:1]]
      and one["top5"] == always["top5"],
      f"crops={len(one['crops'])} top5 같음={one['top5'] == always['top5']}")
check("전제: 재판정에 쓴 확대 영역이 둘 이상이다(그래야 위 비교가 '보여 주는 수'를 가른다)",
      len(always["crops"]) >= 2, f"crops={len(always['crops'])}")
# 잘라 키우는 경로(안면부)도 보여 주는 수를 지킨다 — 재추론 경로와 달리 확대 영역마다 CAM 을 따로 구하지
# 않아서, 수 제한이 빠지면 고른 곳이 전부 그대로 나간다.
if "facial" in serving.TASK_MODELS:
    try:
        facial_all = serving.run_inference(IMAGES[2], "facial")
        serving.HEATMAP_CROPS = 1
        facial_one = serving.run_inference(IMAGES[2], "facial")
    finally:
        serving.HEATMAP_CROPS = 3
    check("안면부도 HEATMAP_CROPS=1 이면 1번 확대 보기만 나간다",
          len(facial_all["crops"]) >= 2
          and [c["box_norm"] for c in facial_one["crops"]] == [c["box_norm"] for c in facial_all["crops"][:1]],
          f"기본 {len(facial_all['crops'])}장 / 1로 줄이면 {len(facial_one['crops'])}장")
else:
    skip("안면부 보여 주는 수 검사", f"미로드 — {serving.TASK_LOAD_ERRORS.get('facial', '사유 불명')}")

# =============================================
print("\n[7] HEATMAP_SMOOTH 는 색만 바꾼다")

original_smooth = serving.HEATMAP_SMOOTH
try:
    serving.HEATMAP_SMOOTH = 0.07
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01
    smoother = serving.run_inference(IMAGES[0], "skin_lesion")
finally:
    serving.HEATMAP_SMOOTH = original_smooth
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
check("흐림을 바꿔도 확대 영역과 재판정 결과는 그대로이고, 전체 히트맵 색만 달라진다",
      [c["box_norm"] for c in smoother["crops"]] == [c["box_norm"] for c in always["crops"]]
      and smoother["top5"] == always["top5"] and smoother["full_image_top1"] == always["full_image_top1"]
      and smoother["heatmap_base64"] != always["heatmap_base64"],
      f"영역 {[c['box_norm'] for c in smoother['crops']]} vs {[c['box_norm'] for c in always['crops']]}")

# =============================================
print("\n[8] 요청의 crops=False")

try:
    serving.LOW_CONFIDENCE_THRESHOLD = 1.01
    off = serving.run_inference(IMAGES[0], "skin_lesion", crops=False)
finally:
    serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
check("crops=False 면 확대 보기도 재판정도 없이 원본 판정과 전체 히트맵만 나간다",
      off["crops"] == [] and off["full_image_top1"] is None
      and off["top1"] == never["top1"] and off["heatmap_base64"] == never["heatmap_base64"],
      f"crops={len(off['crops'])} full={off['full_image_top1']} top1={off['top1']}")

# =============================================
print("\n[9] 1순위가 바뀌는 경로를 여러 스레드가 동시에")

# run_inference 는 모델 락을 세 번까지 잡는다(원본 CAM, 확대 영역 재추론, 1순위가 바뀌면 그 확대 영역 재추론).
# 마지막 구간은 위 [3] 처럼 1순위가 바뀔 때만 지나므로, 그 경로를 동시에 태워야 락이 빠진 고장이 드러난다
# (락이 없으면 다른 요청의 hook 이 activation 을 바꿔 끼워 예외가 나고, 조용히 원본 판정으로 돌아간다).
if target is not None:
    def signature(r):
        return (r["top1"], r["full_image_top1"], r["heatmap_base64"],
                [(c["box_norm"], c["heatmap_base64"]) for c in r["crops"]])

    out: list = [None] * 4
    try:
        serving._crop_forward = biased_crop_forward
        serving.LOW_CONFIDENCE_THRESHOLD = 1.01
        sequential = [signature(serving.run_inference(IMAGES[i % 2], "skin_lesion")) for i in range(2)]

        def work(i: int) -> None:
            out[i] = signature(serving.run_inference(IMAGES[i % 2], "skin_lesion"))

        threads = [threading.Thread(target=work, args=(i,)) for i in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    finally:
        serving._crop_forward = real_crop_forward
        serving.LOW_CONFIDENCE_THRESHOLD = ORIGINAL_THRESHOLD
    check("동시에 돌려도 한 줄로 돌린 결과와 같다(1순위·원본 1순위·전체 히트맵·확대 히트맵)",
          all(out[i] == sequential[i % 2] for i in range(4)),
          f"같은 것 {[out[i] == sequential[i % 2] for i in range(4)]}")
    check("그 경로를 실제로 탔다(IMAGES[1] 의 1순위가 바뀌었다)",
          sequential[1][0]["disease_code"] == CLASSES[target], f"{sequential[1][0]}")
else:
    skip("동시 실행 검사", "전제 실패 — 위에서 이미 실패로 셌다")

# =============================================
print()
if failures:
    print(f"❌ 실패 {len(failures)}건: {', '.join(failures)}")
    sys.exit(1)
print(f"✅ 모두 통과 (건너뜀 {len(skipped)}건)")
