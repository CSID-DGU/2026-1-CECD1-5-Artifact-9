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

사용법
------
    # 서버를 띄운 상태에서
    docker compose exec -T fastapi python tests/test_concurrent_heatmap.py

    # 다른 주소를 보려면
    TARGET=http://localhost:8000 python tests/test_concurrent_heatmap.py
"""

import base64
import io
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageDraw

TARGET = os.getenv("TARGET", "http://127.0.0.1:8000")
ROUNDS = int(os.getenv("ROUNDS", "20"))


def make_image(seed: int, size=(320, 320)) -> str:
    """서로 확실히 다른 테스트 이미지를 만든다 (base64 JPEG)."""
    img = Image.new("RGB", size, (30 + seed * 60, 20, 40))
    draw = ImageDraw.Draw(img)
    for i in range(6):
        offset = i * 22 + seed * 11
        draw.ellipse(
            [offset, offset, size[0] - offset, size[1] - offset],
            outline=(200 - i * 20, 60 + seed * 50, 90 + i * 15),
            width=7,
        )
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return base64.b64encode(buf.getvalue()).decode()


def predict(image_b64: str) -> dict:
    req = urllib.request.Request(
        f"{TARGET}/predict-base64",
        data=json.dumps({"image_base64": image_b64}).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return {"ok": True, "body": json.loads(resp.read())}
    except urllib.error.HTTPError as e:
        return {"ok": False, "status": e.code, "body": e.read().decode()[:200]}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "status": "EXC", "body": repr(e)[:200]}


def main() -> int:
    images = [make_image(0), make_image(1)]

    # ── 1. 기준값: 한 번에 하나씩만 보낸다 ──────────────────────────
    print("1. 기준값 수집 (순차 요청)")
    baseline = []
    for i, img in enumerate(images):
        r = predict(img)
        if not r["ok"]:
            print(f"   ✗ 이미지 {i} 순차 요청부터 실패: {r}")
            return 1
        h = r["body"]["heatmap_base64"]
        if h is None:
            print(f"   ✗ 이미지 {i} 순차 요청에서 히트맵이 None — 서버 설정을 먼저 확인하세요")
            return 1
        baseline.append(h)
        print(f"   이미지 {i}: top1={r['body']['top1']['disease_code']} "
              f"히트맵 {len(h)}바이트")

    # 추론이 결정적인지 확인 (이 전제가 깨지면 아래 비교가 무의미)
    for i, img in enumerate(images):
        again = predict(img)["body"]["heatmap_base64"]
        if again != baseline[i]:
            print(f"   ✗ 이미지 {i}: 같은 입력인데 순차 결과가 매번 다름 → 이 검증법 사용 불가")
            return 1
    print("   ✓ 순차 결과는 재현 가능(결정적)\n")

    # ── 2. 동시 요청 ────────────────────────────────────────────────
    print(f"2. 서로 다른 이미지 2장을 동시에 {ROUNDS}회 요청")
    http_error = 0
    heatmap_none = 0
    heatmap_mismatch = 0

    with ThreadPoolExecutor(max_workers=2) as pool:
        for _ in range(ROUNDS):
            results = list(pool.map(predict, images))
            for idx, r in enumerate(results):
                if not r["ok"]:
                    http_error += 1
                    continue
                h = r["body"]["heatmap_base64"]
                if h is None:
                    heatmap_none += 1
                elif h != baseline[idx]:
                    heatmap_mismatch += 1

    total = ROUNDS * len(images)
    corrupted = http_error + heatmap_none + heatmap_mismatch

    print(f"\n   총 요청           : {total}")
    print(f"   HTTP 실패         : {http_error}")
    print(f"   히트맵 None       : {heatmap_none}")
    print(f"   히트맵 내용 불일치 : {heatmap_mismatch}")
    print(f"   ─────────────────────────────")
    print(f"   오염된 응답        : {corrupted} / {total}")

    if corrupted:
        print("\n✗ 실패 — 동시 요청이 서로의 결과를 오염시키고 있습니다.")
        return 1

    print("\n✓ 통과 — 동시 요청 결과가 단독 요청 결과와 모두 일치합니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
