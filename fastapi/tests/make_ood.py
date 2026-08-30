"""
OOD(Out-Of-Distribution) 평가 세트를 만든다 — "피부 병변 사진이 아닌 것" 모음.

왜 필요한가.
  신뢰도 임계값이 원래 맡았던 일은 정확도를 올리는 게 아니라 병변 사진이 아닌 것을
  걸러내는 것이었다. 그런데 지금까지의 모든 측정은 병변 사진으로만 이뤄져 있었다.
  거기서 나오던 '거절률'은 전부 **오거절률**, 즉 그 차단의 비용이다.

  비용만 재고 효과는 한 번도 안 쟀다. 0.45 를 0.35 로 내린 결정도 마찬가지다 —
  오거절이 17.8% 에서 5.4% 로 준다는 것만 알았지, 0.35 가 여전히 제 역할을 하는지는
  모르는 채였다. 벽 사진이 'mel 40%' 로 통과해도 기존 측정으로는 보이지 않는다.

  이 스크립트가 그 반대편을 잰다. 여기 나오는 사진은 **전부 걸러지는 것이 정답**이다.

  그리고 이 세트가 그 차단을 없앴다. 재 보니 0.35 에서 OOD 의 79.6% 가 그냥 통과했다.
  걸러내라고 둔 장치가 못 걸러내면서 흑색종 재현율만 깎고 있었으므로(89.3% → 88.8%,
  0.45 에서는 86.3%), 차단을 걷어내고 경고로 바꿨다 — main.py 의
  LOW_CONFIDENCE_THRESHOLD 주석 참고. 이 세트의 쓸모는 그대로다. 부호만 바뀌어서,
  이제는 "경고가 붙는 것이 정답"인 사진 모음이다.

무엇을 만드나 — 네 갈래로 나눈다. 갈래별로 난이도가 다르고, 섞으면 해석이 안 된다.

  normal_skin  정상 피부. PAD-UFES-20 사진의 모서리를 잘라 쓴다.
               **이 세트에서 제일 중요하고 제일 어려운 갈래다.** 키오스크에서 실제로
               일어나는 오촬영이 바로 이것 — 아무것도 없는 팔뚝을 찍는 경우다.
               색·질감이 학습 데이터와 같아서, 모델이 "피부처럼 생겼으니 병변"이라고
               답해 버리기 가장 쉬운 입력이다.

  degenerate   촬영 실패. 렌즈 가림(검정), 과노출(흰색), 초점 실패(흐림),
               저조도 노이즈, 손떨림(모션 블러).

  pattern      사물·직물·문서. 줄무늬·체크(옷), 텍스트(서류), 격자(화면·타일).
               규칙적인 고주파 무늬라 피부 사진과 통계가 확연히 다르다 — 쉬운 쪽이다.

  surface      나무결·골판지·시멘트·모래·가죽·직물. **색은 피부색인데 피부가 아닌 것**이라
               pattern 보다 훨씬 어렵다 (실제로 경고율이 제일 낮게 나온다).
               프랙탈 노이즈로 만들어 자연 표면과 같은 1/f 스펙트럼을 가진다.

  photo        비피부 실사진(풍경 등). --photo-dir 로 폴더를 줄 때만 만든다.
               합성 이미지에는 없는 자연스러운 통계를 가진 입력이라 의미가 있지만,
               출처가 로컬 파일이라 **재현이 안 된다** — 참고용 갈래로만 쓴다.

재현성.
  normal_skin 은 baselines/holdout.csv 의 PAD 사진에서만 뽑는다. 이유가 둘 있다.
    1. 홀드아웃이므로 모델이 그 사진을 한 번도 본 적이 없다. 학습에 쓴 사진의
       모서리를 잘라 쓰면 "처음 보는 정상 피부"를 재는 게 아니게 된다.
    2. 목록이 저장소에 있으므로 누구든 같은 크롭을 다시 만들 수 있다.
  degenerate·pattern·surface 는 시드 고정 합성이라 항상 같은 그림이 나온다
  (두 번 돌려 파일 해시가 바이트 단위로 같은 것을 확인했다).
  만든 결과는 ood_manifest.csv 에 출처까지 적어 남긴다.

한계 — 정직하게 적어 둔다.
  normal_skin 은 "병변을 찍으려고" 든 카메라의 가장자리라, 평균적인 피부보다
  햇빛 손상이 많고 나이 든 피부에 치우쳐 있다.
  선별을 통과한 391장 중 96장을 무작위로 골라 눈으로 확인했더니, 병변으로 볼
  여지가 있는 것이 5장 정도(약 5%) 있었다 — 각질 낀 가장자리, 오돌토돌한 융기,
  번들거리는 점막처럼 애매한 것들이다. 피부과 전문의 판정이 아니라 육안 판정이다.
  즉 이 갈래의 통과율은 **상한**이다. 참고로 선별 기준을 조인 것(색 분산만 보던
  옛 기준 → 피부 비율·잉크·얼룩 3단 기준)이 443장을 391장으로 줄였는데, 그때
  빠진 52장은 대부분 모델이 bkl(지루각화증 = 색소 얼룩)로 부르던 것들이었다.
  얼룩을 골라내는 필터가 실제로 얼룩을 골라냈다는 뜻이다.

  degenerate·pattern·surface 는 합성이다. 진짜 손·얼굴·옷·음식 사진이 아니다.
  합성이라 재현되고 라벨이 확실하다는 장점과, 실제 카메라의 통계가 아니라는
  단점을 같이 가진다. photo 갈래가 그 반대편이지만 재현이 안 된다.

실행 (컨테이너 안에서 돈다 — PIL·numpy 만 쓴다):

  docker compose run --rm --no-deps \\
    -v /path/to/pad-ufes-20-small/images:/pad:ro \\
    -v "$PWD/fastapi/tests:/app/tests" \\
    fastapi python tests/make_ood.py --pad-dir /pad --out /app/tests/ood
"""
import argparse
import csv
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SIZE = 512          # PAD-UFES-20 과 같은 한 변. 서빙 transform 이 어차피 224 로 줄인다.
JPEG_QUALITY = 90   # 실제 사진과 비슷한 압축 흔적을 남긴다


# =============================================
# normal_skin — PAD 사진의 모서리
# =============================================
# 모서리 선별 기준. 아래 숫자는 PAD 홀드아웃 459장 × 네 모서리 = 1836개를 실제로
# 측정하고, 구간별 크롭을 눈으로 확인해서 정했다 (측정값은 각 항목 주석에).
MIN_SKIN_FRACTION = 0.90   # 픽셀 단위 피부색 비율
MAX_INK_FRACTION = 0.02    # 파란 잉크(시술 표시용 펜) 픽셀 비율
MAX_BLOB = 20.0            # '주변보다 어두운 덩어리'의 깊이 (0~255 밝기 기준)


def _corner_score(crop: Image.Image) -> tuple[float, str] | None:
    """
    이 모서리가 '병변 없는 정상 피부'인가. 통과하면 (점수, 설명), 실격이면 None.
    점수는 한 사진의 네 모서리 중 하나를 고르는 데만 쓴다 — 낮을수록 깨끗하다.
    """
    a = np.asarray(crop, dtype=np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]

    # 1) 피부색 픽셀의 비율. 예전에는 채널 '평균'만 봤는데, 그러면 절반이 배경(천·풀·
    #    머리카락)인 모서리도 평균이 피부색이면 통과했다. 픽셀마다 센다.
    #    측정: 이 값의 분위수는 통과 모서리 [0.62, 0.94, 1.0] (25/50/75), 탈락 모서리
    #    [0.03, 0.16, 0.30] 로 갈려서, 0.90 은 둘 사이의 넓은 빈 구간에 있다.
    skin = float(((r > g) & (g > b) & (r > 60) & (r < 245) & (g > 35)).mean())
    if skin < MIN_SKIN_FRACTION:
        return None

    # 2) 시술 표시용 펜 자국. 피부에는 B >= R 인 픽셀이 사실상 없다.
    if float((b >= r).mean()) > MAX_INK_FRACTION:
        return None

    # 3) 점·주근깨·혈관. 대역통과(가는 블러 - 굵은 블러)로 본다.
    #    가는 블러가 털을 지우고, 굵은 블러를 빼면 조명 기울기와 비네팅이 빠진다.
    #    남는 것이 딱 '점 크기의 얼룩'이다.
    #    이 단계를 대역통과로 하는 이유: 그냥 '가장 어두운 픽셀'로 재면 1등부터
    #    꼴등까지 전부 털이 결정한다. 털 많은 피부도 정상 피부인데 그것만 잘려나가
    #    세트가 민머리 피부로 치우친다 (실제로 그렇게 나왔다).
    #    측정: 통과 모서리의 분위수 50/75/90/95 = 12 / 16 / 20 / 23.
    #    상위 10%(=20 초과) 크롭을 눈으로 보면 점·펜자국·굵은 혈관이 몰려 있다.
    small = crop.resize((128, 128), Image.LANCZOS).convert("L")
    fine = np.asarray(small.filter(ImageFilter.GaussianBlur(4)), dtype=np.float32)
    coarse = np.asarray(small.filter(ImageFilter.GaussianBlur(20)), dtype=np.float32)
    band = (fine - coarse)[16:-16, 16:-16]      # 블러가 만든 가장자리 인공물은 버린다
    blob = float(-band.min())
    if blob > MAX_BLOB:
        return None

    std = float(a.reshape(-1, 3).std(axis=0).mean())
    return blob + std / 4.0, f"skin={skin:.2f} blob={blob:.0f} std={std:.0f}"


def build_normal_skin(pad_dir: Path, holdout_csv: Path, out_dir: Path) -> list[dict]:
    """PAD 홀드아웃 사진마다 네 모서리 중 가장 깨끗한 것을 하나 고른다.

    한 사진에서 한 장만 쓴다. 두 모서리를 다 쓰면 장수는 늘지만 같은 사람의 같은
    피부·같은 조명이라 서로 독립이 아니다 — 거절률의 신뢰구간만 실제보다 좁아진다.
    """
    with holdout_csv.open(encoding="utf-8", newline="") as fh:
        pad_ids = [r["image_id"] for r in csv.DictReader(fh) if r["src"] == "pad"]
    if not pad_ids:
        raise SystemExit(f"{holdout_csv} 에 src=pad 인 행이 없습니다")

    out_dir.mkdir(parents=True, exist_ok=True)
    rows, missing, dropped = [], 0, 0
    for image_id in pad_ids:
        source = pad_dir / image_id
        if not source.exists():
            missing += 1
            continue
        im = Image.open(source).convert("RGB")
        w, h = im.size
        cw, ch = w // 4, h // 4
        boxes = {"tl": (0, 0, cw, ch),         "tr": (w - cw, 0, w, ch),
                 "bl": (0, h - ch, cw, h),     "br": (w - cw, h - ch, w, h)}

        best = None
        for name, box in sorted(boxes.items()):
            scored = _corner_score(im.crop(box))
            if scored is not None and (best is None or scored[0] < best[1]):
                best = (name, scored[0], scored[1], box)

        if best is None:
            dropped += 1        # 네 모서리가 전부 실격 = 병변이 프레임을 꽉 채운 사진
            continue

        corner, _, detail, box = best
        name = f"{Path(image_id).stem}_{corner}.jpg"
        im.crop(box).resize((SIZE, SIZE), Image.LANCZOS).save(
            out_dir / name, quality=JPEG_QUALITY)
        rows.append({"file": f"normal_skin/{name}", "category": "normal_skin",
                     "source": image_id, "detail": f"corner={corner} {detail}"})

    print(f"  normal_skin {len(rows)}장 "
          f"(깨끗한 모서리가 없어 제외 {dropped}장, 원본 없음 {missing}장)")
    return rows


# =============================================
# degenerate / pattern — 합성
# =============================================
def _save(img: Image.Image, out_dir: Path, category: str, name: str,
          detail: str, rows: list[dict]) -> None:
    img.convert("RGB").save(out_dir / name, quality=JPEG_QUALITY)
    rows.append({"file": f"{category}/{name}", "category": category,
                 "source": "(합성)", "detail": detail})


def build_degenerate(out_dir: Path, rng: np.random.Generator) -> list[dict]:
    """촬영이 실패한 사진들. 키오스크에서 흔히 나오는 실패다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    full = (SIZE, SIZE)

    # 단색 — 렌즈 가림(검정), 과노출(흰색), 벽·천장(회색·베이지)
    for i, (name, rgb) in enumerate([
            ("black", (4, 4, 4)), ("white", (252, 252, 252)),
            ("grey", (128, 128, 128)), ("beige", (214, 196, 172)),
            ("blue", (60, 90, 170)), ("green", (70, 140, 80))]):
        _save(Image.new("RGB", full, rgb), out_dir, "degenerate",
              f"solid_{name}.jpg", f"단색 rgb{rgb}", rows)

    # 저조도 노이즈 — 어두운 곳에서 찍으면 센서 노이즈만 남는다
    for i in range(12):
        level = 10 + i * 4
        arr = rng.normal(level, 14, (SIZE, SIZE, 3)).clip(0, 255).astype(np.uint8)
        _save(Image.fromarray(arr), out_dir, "degenerate",
              f"lowlight_noise_{i}.jpg", f"저조도 노이즈 평균{level}", rows)

    # 균일 노이즈 — 신호가 전혀 없는 입력
    for i in range(8):
        arr = rng.integers(0, 256, (SIZE, SIZE, 3), dtype=np.uint8)
        _save(Image.fromarray(arr), out_dir, "degenerate",
              f"uniform_noise_{i}.jpg", "균일 난수", rows)

    # 초점 실패 — 색 덩어리를 뭉갠다. 형태 정보가 사라진 상태.
    for i in range(12):
        small = rng.integers(60, 220, (8, 8, 3), dtype=np.uint8)
        img = Image.fromarray(small).resize(full, Image.BICUBIC) \
                   .filter(ImageFilter.GaussianBlur(radius=28))
        _save(img, out_dir, "degenerate", f"defocus_{i}.jpg", "초점 실패", rows)

    # 손떨림 — 가로 방향으로 끌린 자국
    for i in range(10):
        small = rng.integers(50, 230, (4, 40, 3), dtype=np.uint8)
        img = Image.fromarray(small).resize(full, Image.BILINEAR) \
                   .filter(ImageFilter.GaussianBlur(radius=6))
        _save(img, out_dir, "degenerate", f"motion_blur_{i}.jpg", "모션 블러", rows)

    # 과노출·암부 뭉갬 — 자동 노출이 실패한 경우
    for i in range(12):
        base = rng.integers(0, 256, (SIZE, SIZE, 3)).astype(np.float32)
        arr = (base * 0.25 + 235) if i % 2 == 0 else (base * 0.12)
        _save(Image.fromarray(arr.clip(0, 255).astype(np.uint8)), out_dir,
              "degenerate", f"exposure_fail_{i}.jpg",
              "과노출" if i % 2 == 0 else "암부 뭉갬", rows)

    # 플래시 정반사 — 피부에 바짝 대고 찍으면 가운데가 통째로 날아간다.
    # 색은 피부색인데 형태 정보만 사라진, 실제로 제일 흔한 실패다.
    yy, xx = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32)
    for i in range(10):
        tint = np.array([rng.integers(150, 225), rng.integers(110, 180),
                         rng.integers(95, 160)], dtype=np.float32)
        cx, cy = rng.integers(150, 362, 2)
        radius = float(rng.integers(90, 220))
        halo = np.exp(-(((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * radius ** 2)))
        arr = tint[None, None, :] * (0.75 + 0.25 * rng.random((SIZE, SIZE, 1)))
        arr = arr + (255.0 - arr) * halo[..., None]
        _save(Image.fromarray(arr.clip(0, 255).astype(np.uint8)), out_dir,
              "degenerate", f"flash_glare_{i}.jpg", "플래시 정반사", rows)

    print(f"  degenerate {len(rows)}장")
    return rows


def build_pattern(out_dir: Path, rng: np.random.Generator) -> list[dict]:
    """사물·직물·화면. 사람이 카메라를 엉뚱한 데 대는 경우다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    full = (SIZE, SIZE)

    # 줄무늬·체크 — 옷감
    for i in range(16):
        period = int(rng.integers(8, 40))
        c1 = tuple(int(v) for v in rng.integers(30, 230, 3))
        c2 = tuple(int(v) for v in rng.integers(30, 230, 3))
        img = Image.new("RGB", full, c1)
        d = ImageDraw.Draw(img)
        for x in range(0, SIZE, period * 2):
            d.rectangle([x, 0, x + period, SIZE], fill=c2)
        if i % 2:                              # 절반은 체크로
            for y in range(0, SIZE, period * 2):
                d.rectangle([0, y, SIZE, y + period], fill=c2)
        _save(img, out_dir, "pattern", f"fabric_{i}.jpg",
              f"{'체크' if i % 2 else '줄무늬'} 주기{period}", rows)

    # 문서 — 흰 바탕에 검은 글줄
    for i in range(14):
        img = Image.new("RGB", full, (250, 249, 246))
        d = ImageDraw.Draw(img)
        y = int(rng.integers(20, 60))
        while y < SIZE - 20:
            width = int(rng.integers(SIZE // 3, SIZE - 40))
            d.rectangle([30, y, 30 + width, y + int(rng.integers(5, 11))],
                        fill=(40, 40, 45))
            y += int(rng.integers(22, 40))
        _save(img, out_dir, "pattern", f"document_{i}.jpg", "문서·텍스트", rows)

    # 격자 — 화면·타일·창틀
    for i in range(16):
        step = int(rng.integers(24, 70))
        img = Image.new("RGB", full, tuple(int(v) for v in rng.integers(90, 240, 3)))
        d = ImageDraw.Draw(img)
        line = tuple(int(v) for v in rng.integers(10, 90, 3))
        for p in range(0, SIZE, step):
            d.line([(p, 0), (p, SIZE)], fill=line, width=3)
            d.line([(0, p), (SIZE, p)], fill=line, width=3)
        _save(img, out_dir, "pattern", f"grid_{i}.jpg", f"격자 간격{step}", rows)

    print(f"  pattern {len(rows)}장")
    return rows


def _fractal(rng: np.random.Generator, octaves: int, aniso: float) -> np.ndarray:
    """옥타브를 겹친 프랙탈 노이즈 (0~1). aniso>1 이면 가로로 늘어난다(나뭇결)."""
    acc = np.zeros((SIZE, SIZE), dtype=np.float32)
    amp = 1.0
    for o in range(octaves):
        n = 2 ** (o + 2)
        h = max(2, int(round(n / aniso)))
        layer = (rng.random((h, n)) * 255).astype(np.uint8)
        up = Image.fromarray(layer).resize((SIZE, SIZE), Image.BICUBIC)
        acc += amp * (np.asarray(up, dtype=np.float32) / 255.0)
        amp *= 0.5
    acc -= acc.min()
    return acc / max(float(acc.max()), 1e-6)


def build_surface(out_dir: Path, rng: np.random.Generator) -> list[dict]:
    """
    피부색과 통계가 비슷한 실제 표면 — 나무·골판지·콘크리트·모래·가죽·직물.

    pattern 갈래(줄무늬·격자·문서)는 사실 쉬운 문제다. 규칙적인 고주파 무늬는
    피부 사진과 통계가 전혀 달라서, 모델이 헷갈릴 이유가 별로 없다.
    진짜 어려운 건 **색은 피부색인데 피부가 아닌 것**이다 — 책상 나무결, 골판지
    상자, 시멘트 벽. 키오스크를 아무 데나 대면 실제로 나오는 그림이기도 하다.
    프랙탈 노이즈로 만들기 때문에 자연 표면처럼 1/f 스펙트럼을 가진다.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    # (이름, 기본색, 옥타브, 이방성, 대비)
    kinds = [
        ("wood",     (152, 106,  62), 6, 14.0, 0.55),   # 나뭇결 — 한 방향으로 길게
        ("cardboard",(186, 150, 106), 5,  1.0, 0.30),   # 골판지
        ("concrete", (156, 152, 145), 6,  1.0, 0.35),   # 시멘트 벽
        ("sand",     (198, 170, 130), 7,  1.0, 0.45),   # 모래·흙
        ("leather",  (118,  80,  58), 6,  1.4, 0.50),   # 가죽
        ("weave",    (172, 158, 140), 4,  1.0, 0.40),   # 성긴 직물
    ]
    for name, base, octaves, aniso, contrast in kinds:
        for i in range(6):
            field = _fractal(rng, octaves, aniso)
            shade = 1.0 - contrast / 2 + contrast * field       # 0.7~1.3 부근
            tint = np.array(base, dtype=np.float32) * float(rng.uniform(0.85, 1.15))
            arr = tint[None, None, :] * shade[..., None]
            _save(Image.fromarray(arr.clip(0, 255).astype(np.uint8)), out_dir,
                  "surface", f"{name}_{i}.jpg", f"{name} 표면", rows)

    print(f"  surface {len(rows)}장")
    return rows


def build_photo(photo_dir: Path, out_dir: Path) -> list[dict]:
    """비피부 실사진. 출처가 로컬이라 재현되지 않는 참고용 갈래다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for source in sorted(photo_dir.iterdir()):
        if source.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
            continue
        im = Image.open(source).convert("RGB")
        # 가운데를 정사각으로 잘라 크기를 맞춘다 (긴 쪽을 그냥 찌그러뜨리지 않는다)
        w, h = im.size
        side = min(w, h)
        im = im.crop(((w - side) // 2, (h - side) // 2,
                      (w + side) // 2, (h + side) // 2)).resize((SIZE, SIZE), Image.LANCZOS)
        name = f"{source.stem}.jpg"
        im.save(out_dir / name, quality=JPEG_QUALITY)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()[:12]
        rows.append({"file": f"photo/{name}", "category": "photo",
                     "source": source.name, "detail": f"sha256={digest}"})
    print(f"  photo {len(rows)}장 (재현 불가 — 참고용)")
    return rows


# =============================================
def main() -> int:
    ap = argparse.ArgumentParser(description="OOD 평가 세트를 만든다.")
    ap.add_argument("--pad-dir", type=Path, required=True,
                    help="PAD-UFES-20 이미지 폴더 (normal_skin 의 출처)")
    ap.add_argument("--holdout", type=Path,
                    default=Path(__file__).resolve().parent / "baselines/holdout.csv",
                    help="홀드아웃 목록. 여기 있는 PAD 사진만 쓴다.")
    ap.add_argument("--photo-dir", type=Path,
                    help="비피부 실사진 폴더 (선택). 주면 photo 갈래를 만든다.")
    ap.add_argument("--out", type=Path, required=True, help="만들 폴더")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    if not args.pad_dir.is_dir():
        raise SystemExit(f"PAD 폴더가 없습니다: {args.pad_dir}")
    if not args.holdout.exists():
        raise SystemExit(f"홀드아웃 목록이 없습니다: {args.holdout}")

    args.out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)

    print(f"OOD 세트를 만듭니다 → {args.out}")
    rows: list[dict] = []
    rows += build_normal_skin(args.pad_dir, args.holdout, args.out / "normal_skin")
    rows += build_degenerate(args.out / "degenerate", rng)
    rows += build_pattern(args.out / "pattern", rng)
    rows += build_surface(args.out / "surface", rng)
    if args.photo_dir:
        if not args.photo_dir.is_dir():
            raise SystemExit(f"사진 폴더가 없습니다: {args.photo_dir}")
        rows += build_photo(args.photo_dir, args.out / "photo")

    manifest = args.out / "ood_manifest.csv"
    with manifest.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=["file", "category", "source", "detail"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"\n총 {len(rows)}장. 목록: {manifest}")
    print("이 사진들은 **전부 거절되는 것이 정답**이다. 재려면:")
    print(f"  python tests/evaluate.py --ood-dir {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
