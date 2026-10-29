"""
동시 요청 시 Grad-CAM 히트맵이 뒤섞이는지 검증하는 재현 스크립트.

배경
----
Grad-CAM 은 전역 `model` 객체에 forward hook 을 붙여 중간 activation 을 꺼낸다.
모델이 요청마다 새로 만들어지지 않고 서버 전체가 하나를 공유하므로,
A 요청이 hook 을 붙인 상태에서 B 요청이 forward 를 돌리면
**A 의 hook 이 B 의 activation 으로 발화**한다.

추론은 결정적(eval 모드, 난수 없음)이라 같은 입력은 항상 같은 결과를 낸다.
따라서 "혼자 보냈을 때의 결과"를 기준값으로 잡고,
동시에 보냈을 때 결과가 달라지면 오염된 것이다.

히트맵은 바이트가 아니라 **화소**로 비교한다. 역전파(CAM)를 여러 스레드로 돌리면 합산 순서가
실행마다 달라 값이 1e-7 수준으로 흔들리고(1스레드에서는 비트 단위로 같다), 그것이 JPEG 에서
화소 몇 단계로 드러날 수 있다. 교차오염은 남의 CAM 이 오는 것이라 차이의 규모가 다르다 — 두 이미지는
배경이 같고 무늬만 서로 다른 구석에 두어 CAM 이 다른 곳을 짚게 했다. 그 차이가 실제로 보이는지는
히트맵 전체가 아니라 **색 층**(히트맵에서 원본 사진을 뺀 것)끼리 견줘 미리 확인한다 — 히트맵째 견주면
사진이 달라서 늘 달라 보이고, CAM 이 바뀐 것을 잡을 수 있는지는 알 수 없다. 확대 보기(crops)도 같은
hook 을 거치므로 개수·위치·그림을 함께 비교한다.

추론 엔드포인트는 `X-Internal-Secret` 으로 보호돼 있다. 컨테이너 안에서 돌리면
서버와 같은 `INTERNAL_API_SECRET` 환경변수를 그대로 읽으므로 따로 줄 것이 없다.
밖에서 돌릴 때는 직접 넘겨야 한다.

사용법
------
    # 서버를 띄운 상태에서 (컨테이너 안 — 환경변수를 공유하므로 이게 가장 쉽다)
    docker compose exec -T fastapi python tests/test_concurrent_heatmap.py

    # 호스트에서 다른 주소를 보려면 시크릿도 같이 준다
    INTERNAL_API_SECRET=... TARGET=http://localhost:8000 \
        python tests/test_concurrent_heatmap.py
"""

import base64
import io
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageChops, ImageDraw, ImageStat

TARGET = os.getenv("TARGET", "http://127.0.0.1:8000")
ROUNDS = int(os.getenv("ROUNDS", "20"))

# 서버가 기동할 때 요구하는 것과 같은 환경변수다. 컨테이너 안에서 돌리면 서버와 같은
# 값이 이미 들어 있다. 기본값을 두지 않는다 — 틀린 값으로 조용히 401 을 받는 것보다
# 없다고 바로 말하는 편이 낫다. 이 테스트가 오랫동안 401 만 받으며 통과한 적도 없이
# 방치됐던 이유가 헤더 누락이었다.
SECRET = os.getenv("INTERNAL_API_SECRET", "")


def make_image(seed: int, size=(320, 320)) -> str:
    """배경은 같고 동심원 무늬만 서로 다른 구석(0 은 왼쪽 위, 1 은 오른쪽 아래)에 있는 이미지 (base64 JPEG).
    CAM 이 서로 다른 곳을 짚어야 남의 CAM 이 섞여 들어온 것을 그림으로 가려낼 수 있다."""
    img = Image.new("RGB", size, (30, 20, 40))
    draw = ImageDraw.Draw(img)
    cx, cy = (0.3 * size[0], 0.3 * size[1]) if seed == 0 else (0.7 * size[0], 0.7 * size[1])
    for i in range(5):
        r = 90 - i * 16
        draw.ellipse([cx - r, cy - r, cx + r, cy + r],
                     outline=(200 - i * 20, 60 + seed * 50, 90 + i * 15), width=7)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return base64.b64encode(buf.getvalue()).decode()


def predict(image_b64: str) -> dict:
    req = urllib.request.Request(
        f"{TARGET}/predict-base64",
        data=json.dumps({"image_base64": image_b64}).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Internal-Secret": SECRET,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return {"ok": True, "body": json.loads(resp.read())}
    except urllib.error.HTTPError as e:
        return {"ok": False, "status": e.code, "body": e.read().decode()[:200]}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "status": "EXC", "body": repr(e)[:200]}


def _picture(b64: str, size: tuple[int, int] | None = None) -> Image.Image:
    img = Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB")
    return img.resize(size) if size else img


def same_image(a: Image.Image, b: Image.Image) -> bool:
    """같은 그림인가 — 화소 평균 1 단계, 최대 32 단계까지는 같다고 본다(모듈 독스트링 참고)."""
    if a.size != b.size:
        return False
    diff = ImageChops.difference(a, b)
    mean = sum(ImageStat.Stat(diff).mean) / 3
    return mean <= 1.0 and max(hi for _, hi in diff.getextrema()) <= 32


def same_picture(a_b64: str, b_b64: str, size: tuple[int, int] | None = None) -> bool:
    """size 를 주면 둘 다 그 크기로 줄여 비교한다(확대 영역은 상자가 한 칸 밀리면 크기가 1px 다를 수 있다)."""
    return same_image(_picture(a_b64, size), _picture(b_b64, size))


def color_layer(heatmap_b64: str, photo_b64: str) -> Image.Image:
    """히트맵에서 원본 사진을 뺀 것 — CAM 이 칠한 색만 남는다(사진이 같으면 이것만 다르다)."""
    heat = _picture(heatmap_b64)
    return ImageChops.difference(heat, _picture(photo_b64, heat.size))


def mismatch(body: dict, base: dict) -> str | None:
    """기준 응답과 다른 점. 같으면 None."""
    if body.get("heatmap_base64") is None:
        return "heatmap_none"
    if body["top5"] != base["top5"]:
        return "top5"
    if not same_picture(body["heatmap_base64"], base["heatmap_base64"]):
        return "heatmap"
    crops, base_crops = body.get("crops") or [], base.get("crops") or []
    if len(crops) != len(base_crops):
        return "crops"
    for c, b in zip(crops, base_crops):
        # 위치는 64칸 격자에서 고르므로 흔들림으로 한 칸(1/64) 밀릴 수는 있다. 남의 사진이면 훨씬 멀다.
        if max(abs(x - y) for x, y in zip(c["box_norm"], b["box_norm"])) > 0.05:
            return "crops"
        if not same_picture(c["heatmap_base64"], b["heatmap_base64"], size=(64, 64)):
            return "crops"
    return None


def main() -> int:
    if not SECRET:
        print("✗ INTERNAL_API_SECRET 이 비어 있습니다 — 모든 요청이 401 로 떨어집니다.")
        print("  컨테이너 안에서 돌리거나(docker compose exec -T fastapi ...),")
        print("  호스트에서는 INTERNAL_API_SECRET=... 를 앞에 붙이세요.")
        return 1

    images = [make_image(0), make_image(1)]

    # ── 1. 기준값: 한 번에 하나씩만 보낸다 ──────────────────────────
    print("1. 기준값 수집 (순차 요청)")
    baseline = []
    for i, img in enumerate(images):
        r = predict(img)
        if not r["ok"]:
            print(f"   ✗ 이미지 {i} 순차 요청부터 실패: {r}")
            if r.get("status") == 401:
                print("     → 시크릿이 서버의 INTERNAL_API_SECRET 과 다릅니다.")
            return 1
        h = r["body"]["heatmap_base64"]
        if h is None:
            print(f"   ✗ 이미지 {i} 순차 요청에서 히트맵이 None — 서버 설정을 먼저 확인하세요")
            return 1
        baseline.append(r["body"])
        print(f"   이미지 {i}: top1={r['body']['top1']['disease_code']} "
              f"히트맵 {len(h)}바이트, 확대 보기 {len(r['body'].get('crops') or [])}장")

    # 두 이미지의 **CAM** 이 서로 구별되는지 — 색 층이 같아 보이면 남의 CAM 이 와도 아래 비교가 못 잡는다
    layers = [color_layer(baseline[i]["heatmap_base64"], images[i]) for i in range(2)]
    if same_image(*layers):
        print("   ✗ 두 테스트 이미지의 CAM 이 같아 보여 오염을 구별할 수 없음 → 이 검증법 사용 불가")
        return 1
    gap = sum(ImageStat.Stat(ImageChops.difference(*layers)).mean) / 3
    print(f"   두 이미지의 색 층 차이: 화소 평균 {gap:.1f} 단계 (같다고 보는 한계는 1 단계)")

    # 추론이 결정적인지 확인 (이 전제가 깨지면 아래 비교가 무의미)
    for i, img in enumerate(images):
        again = predict(img)
        why = mismatch(again["body"], baseline[i]) if again["ok"] else f"HTTP {again.get('status')}"
        if why:
            print(f"   ✗ 이미지 {i}: 같은 입력인데 순차 결과가 다름({why}) → 이 검증법 사용 불가")
            return 1
    print("   ✓ 순차 결과는 재현 가능(결정적)\n")

    # ── 2. 동시 요청 ────────────────────────────────────────────────
    print(f"2. 서로 다른 이미지 2장을 동시에 {ROUNDS}회 요청")
    http_error = 0
    heatmap_none = 0
    heatmap_mismatch = 0
    crops_mismatch = 0

    with ThreadPoolExecutor(max_workers=2) as pool:
        for _ in range(ROUNDS):
            results = list(pool.map(predict, images))
            for idx, r in enumerate(results):
                if not r["ok"]:
                    http_error += 1
                    continue
                why = mismatch(r["body"], baseline[idx])
                if why == "heatmap_none":
                    heatmap_none += 1
                elif why == "crops":
                    crops_mismatch += 1
                elif why:
                    heatmap_mismatch += 1

    total = ROUNDS * len(images)
    corrupted = http_error + heatmap_none + heatmap_mismatch + crops_mismatch

    print(f"\n   총 요청           : {total}")
    print(f"   HTTP 실패         : {http_error}")
    print(f"   히트맵 None       : {heatmap_none}")
    print(f"   히트맵 내용 불일치 : {heatmap_mismatch}")
    print(f"   확대 보기 불일치   : {crops_mismatch}")
    print(f"   ─────────────────────────────")
    print(f"   오염된 응답        : {corrupted} / {total}")

    if corrupted:
        print("\n✗ 실패 — 동시 요청이 서로의 결과를 오염시키고 있습니다.")
        return 1

    print("\n✓ 통과 — 동시 요청 결과가 단독 요청 결과와 모두 일치합니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
