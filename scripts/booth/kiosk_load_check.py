"""
부스 리허설용 — 키오스크 여러 대가 동시에 분석해도 결과가 섞이지 않는지, 얼마나 기다리는지 **실제 서버**에서 잰다.

왜 필요한가
-----------
백엔드 테스트(KioskBoothFlowTest)는 가짜 FastAPI 로 백엔드의 규칙을 고정한다. 부스 당일의 경로는 그보다 길다 —
Caddy → nginx → 백엔드 → AI 로드밸런서 → 실제 모델 워커 여러 개(+ 다른 EC2). 그 길 전체를 같은 순간에 여러 기기가
지나가도 사진·결과·히트맵이 접수끼리 섞이지 않는지는 실제 서버에서만 확인된다. 이 스크립트가 그 확인이다.

무엇을 보는가
-------------
1. 기준: 사진마다 한 번씩 **순서대로** 분석해 1~5순위와 신뢰도를 적어 둔다.
2. 동시: 기기 수만큼의 접수가 **같은 순간에** 분석한다. 라운드마다 사진을 한 칸씩 돌려 맡긴다 — 접수 A 와 B 가
   서로 바뀌면 사진이 달라 답이 달라지므로 드러난다. 각 응답을 그 사진의 기준과 대조하고, 의사 화면(접수 경로)이
   돌려주는 결과·히트맵이 기기가 받은 것과 같은지도 대조한다.
3. 같은 접수에 두 요청이 겹치면 하나는 409 여야 한다(같은 QR 을 두 기기가 찍은 경우).
4. 기다린 시간(p50·p95·최대)을 라운드별로 낸다.

접수 만들기
-----------
기본은 이 스크립트가 "부스점검-NN" 환자와 접수를 기기 수만큼 만들고, 끝나면 접수를 취소한다(--keep 이면 남긴다).
⚠️ 접수를 만들면 접수증이 자동 출력된다(백엔드 VisitController.create). 프린터 점검을 겸하려면 그대로 두고,
출력하지 않으려면 접수 화면에서 미리 만든 접수의 토큰을 --tokens 로 넘긴다.

계정은 ADMIN 또는 DOCTOR 여야 한다 — 접수 생성(직원 권한)과 의사 화면 조회(진료 권한)를 둘 다 쓴다.
비밀번호는 명령줄이 아니라 환경변수로 받는다(셸 기록에 남지 않게).

사용법
------
    export BOOTH_LOGIN_ID=admin BOOTH_PASSWORD='...'
    python3 scripts/booth/kiosk_load_check.py --base-url https://artifact-prod.duckdns.org \\
        --images ~/booth-photos --devices 3 --rounds 3 --task facial

    ... --task mixed            # 기기마다 얼굴·점 모델을 번갈아
    ... --tokens AbC123xyZ789,QwE456rtY012,ZxC789vbN345   # 이미 있는 접수를 쓴다(접수증 출력 없음, 취소도 안 함)
    ... --comment               # 참고 소견(LLM)까지 동시에 요청해 걸린 시간을 본다(유료 호출이 기기 수만큼 나간다)

--images 폴더에는 서로 다른 사진이 기기 수 이상 있어야 한다(jpg/png). 얼굴 모델을 볼 때는 얼굴 사진이 좋지만,
섞임 확인만 보면 어떤 사진이어도 된다 — 사진마다 답이 다르기만 하면 된다.
종료 코드: 섞임·오류가 하나라도 있으면 1.
"""

from __future__ import annotations  # 맥 기본 python3(3.9)에서도 `X | None` 표기가 돌게

import argparse
import hashlib
import json
import mimetypes
import os
import ssl
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
# 같은 사진이라도 워커마다 스레드 수가 달라 소수 넷째 자리가 다를 수 있다(부동소수 합의 순서). 그 이상은 다른 답이다.
CONF_TOLERANCE = 0.01


class Api:
    def __init__(self, base_url: str, timeout: float):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None
        self.ctx = ssl.create_default_context()

    def call(self, method: str, path: str, body: bytes | None = None, content_type: str | None = None,
             auth: bool = False) -> tuple[int, bytes, float]:
        req = urllib.request.Request(self.base + path, data=body, method=method)
        if content_type:
            req.add_header("Content-Type", content_type)
        if auth and self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self.ctx) as r:
                return r.status, r.read(), time.monotonic() - started
        except urllib.error.HTTPError as e:
            return e.code, e.read(), time.monotonic() - started

    def json(self, method: str, path: str, payload: dict | None = None, auth: bool = True) -> dict:
        body = json.dumps(payload).encode() if payload is not None else None
        status, data, _ = self.call(method, path, body, "application/json" if body else None, auth)
        if status >= 300:
            raise SystemExit(f"{method} {path} → {status}: {data[:300].decode(errors='replace')}")
        return json.loads(data) if data else {}

    def analyze(self, kiosk_token: str, image: Path, task: str) -> tuple[int, dict | str, float]:
        boundary = uuid.uuid4().hex
        mime = mimetypes.guess_type(image.name)[0] or "image/jpeg"
        parts = [
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"analysisType\"\r\n\r\n{task}\r\n".encode(),
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{image.name}\"\r\n"
            f"Content-Type: {mime}\r\n\r\n".encode() + image.read_bytes() + b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
        status, data, took = self.call("POST", f"/api/kiosk/session/{kiosk_token}/analyze", b"".join(parts),
                                       f"multipart/form-data; boundary={boundary}")
        try:
            return status, json.loads(data), took
        except ValueError:
            return status, data[:200].decode(errors="replace"), took


def topk(result: dict) -> list[tuple[str, float]]:
    return [(t["diseaseCode"], round(float(t["confidence"]), 4)) for t in result.get("topK", [])]


def same_answer(a: list[tuple[str, float]], b: list[tuple[str, float]]) -> bool:
    return [c for c, _ in a] == [c for c, _ in b] and all(abs(x - y) <= CONF_TOLERANCE for (_, x), (_, y) in zip(a, b))


def pct(values: list[float], q: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--devices", type=int, default=3)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--task", choices=["facial", "skin_lesion", "mixed"], default="facial")
    ap.add_argument("--tokens", help="쉼표로 구분한 기존 키오스크 토큰. 주면 접수를 만들지도 취소하지도 않는다")
    ap.add_argument("--comment", action="store_true", help="참고 소견(LLM)까지 동시에 요청한다")
    ap.add_argument("--keep", action="store_true", help="만든 접수를 취소하지 않고 남긴다")
    ap.add_argument("--timeout", type=float, default=90.0)
    args = ap.parse_args()

    images = sorted(p for p in args.images.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
    if len(images) < args.devices:
        raise SystemExit(f"사진이 {len(images)}장뿐이다. 기기 수({args.devices}) 이상 필요하다.")
    # 라운드마다 한 칸씩 돌려 맡기므로 '기기 수 + 라운드 - 1' 장이면 라운드마다 조합이 다르다. 더 쓰면 기준 분석만 길어진다.
    images = images[: args.devices + args.rounds - 1]
    login_id, password = os.environ.get("BOOTH_LOGIN_ID"), os.environ.get("BOOTH_PASSWORD")
    if not login_id or not password:
        raise SystemExit("BOOTH_LOGIN_ID / BOOTH_PASSWORD 환경변수가 필요하다 (ADMIN 또는 DOCTOR 계정).")

    api = Api(args.base_url, args.timeout)
    api.token = api.json("POST", "/api/v1/auth/login", {"loginId": login_id, "password": password}, auth=False)["token"]

    # ── 접수 준비 ─────────────────────────────────────────────
    created: list[int] = []
    if args.tokens:
        tokens = [t.strip() for t in args.tokens.split(",") if t.strip()][: args.devices]
        if len(tokens) < args.devices:
            raise SystemExit(f"토큰이 {len(tokens)}개뿐이다. --devices {args.devices} 만큼 필요하다.")
        visit_ids = [api.json("GET", f"/api/kiosk/session/{t}", auth=False)["visitId"] for t in tokens]
    else:
        tokens, visit_ids = [], []
        stamp = time.strftime("%H%M")
        for i in range(args.devices):
            patient = api.json("POST", "/api/v1/patients",
                               {"name": f"부스점검-{stamp}-{i + 1:02d}", "birthDate": "2000-01-01", "gender": "M"})
            visit = api.json("POST", "/api/v1/visits", {"patientId": patient["id"], "receptionMemo": "부스 동시성 점검"})
            tokens.append(visit["kioskToken"])
            visit_ids.append(visit["id"])
            created.append(visit["id"])
        print(f"접수 {len(tokens)}건을 만들었다 (접수증이 출력될 수 있다): {visit_ids}")

    def task_for(device: int) -> str:
        if args.task != "mixed":
            return args.task
        return "facial" if device % 2 == 0 else "skin_lesion"

    problems: list[str] = []
    lock = threading.Lock()

    def problem(msg: str) -> None:
        with lock:
            problems.append(msg)
            print(f"  ✗ {msg}")

    try:
        # ── 1. 기준: 사진마다 한 번씩, 순서대로 ──────────────────
        print(f"\n[기준] 사진 {len(images)}장을 하나씩 분석한다")
        baseline: dict[tuple[str, str], list[tuple[str, float]]] = {}
        tasks = sorted({task_for(d) for d in range(args.devices)})
        for task in tasks:
            for img in images:
                status, body, took = api.analyze(tokens[0], img, task)
                if status != 200:
                    raise SystemExit(f"기준 분석 실패 {img.name} {task}: {status} {body}")
                baseline[(img.name, task)] = topk(body)
                print(f"  {task:11s} {img.name:40.40s} {baseline[(img.name, task)][0]}  {took:5.1f}s")

        # ── 2. 동시: 라운드마다 사진을 한 칸씩 돌려 맡긴다 ───────
        all_waits: list[float] = []
        for rnd in range(args.rounds):
            plan = [(d, images[(d + rnd) % len(images)], task_for(d)) for d in range(args.devices)]
            start = threading.Barrier(args.devices)

            def one(device: int, img: Path, task: str):
                start.wait()
                return device, img, task, api.analyze(tokens[device], img, task)

            with ThreadPoolExecutor(max_workers=args.devices) as pool:
                outcomes = list(pool.map(lambda p: one(*p), plan))

            waits = []
            for device, img, task, (status, body, took) in outcomes:
                waits.append(took)
                if status != 200:
                    problem(f"라운드 {rnd + 1} 기기 {device + 1}: HTTP {status} {body}")
                    continue
                got, want = topk(body), baseline[(img.name, task)]
                if not same_answer(got, want):
                    problem(f"라운드 {rnd + 1} 기기 {device + 1}: {img.name} 의 답이 기준과 다르다 got={got[:2]} want={want[:2]}")
                if body.get("analysisType") != task:
                    problem(f"라운드 {rnd + 1} 기기 {device + 1}: 고른 모델 {task} 와 결과 모델 {body.get('analysisType')} 이 다르다")
                # 의사 화면이 같은 결과·같은 히트맵을 보는가 — 저장된 행과 기기가 받은 응답의 대조
                doctor = api.json("GET", f"/api/v1/visits/{visit_ids[device]}/preliminary")
                if not same_answer(topk(doctor), got):
                    problem(f"라운드 {rnd + 1} 기기 {device + 1}: 의사 화면 결과가 기기와 다르다 {topk(doctor)[:2]} vs {got[:2]}")
                if body.get("gradcamUrl") and doctor.get("gradcamUrl"):
                    s1, tablet_img, _ = api.call("GET", body["gradcamUrl"])
                    s2, doctor_img, _ = api.call("GET", doctor["gradcamUrl"], auth=True)
                    if s1 != 200 or s2 != 200 or hashlib.sha256(tablet_img).digest() != hashlib.sha256(doctor_img).digest():
                        problem(f"라운드 {rnd + 1} 기기 {device + 1}: 기기와 의사 화면의 히트맵이 다르다 ({s1}/{s2})")
            all_waits += waits
            print(f"[동시 {rnd + 1}/{args.rounds}] 기기 {args.devices}대  대기 p50 {pct(waits, .5):5.1f}s  "
                  f"최대 {max(waits):5.1f}s")

        # ── 3. 같은 접수에 두 요청이 겹치면 하나는 409 ───────────
        def delayed(delay: float):
            time.sleep(delay)
            return api.analyze(tokens[0], images[0], task_for(0))[0]

        with ThreadPoolExecutor(max_workers=2) as pool:
            codes = sorted(pool.map(delayed, (0.0, 0.3)))
        if codes == [200, 409]:
            print("\n[같은 접수 동시] 200 + 409 — 겹친 요청을 막았다")
        elif codes == [200, 200]:
            print("\n[같은 접수 동시] 200 + 200 — 첫 요청이 0.3초 안에 끝나 겹치지 않았다(확인 불가, 문제 아님)")
        else:
            problem(f"같은 접수 동시 요청 결과가 예상과 다르다: {codes}")

        # ── 4. 참고 소견(선택) ──────────────────────────────────
        if args.comment:
            with ThreadPoolExecutor(max_workers=args.devices) as pool:
                comments = list(pool.map(lambda t: api.call("POST", f"/api/kiosk/session/{t}/comment"), tokens))
            for i, (status, data, took) in enumerate(comments):
                text = json.loads(data).get("aiComment", "") if status == 200 else data[:120]
                print(f"[소견] 기기 {i + 1}: HTTP {status} {took:5.1f}s  {str(text)[:60]!r}")
                if status != 200:
                    problem(f"소견 기기 {i + 1}: HTTP {status}")

        print(f"\n전체 동시 분석 {len(all_waits)}건  대기 p50 {pct(all_waits, .5):.1f}s  p95 {pct(all_waits, .95):.1f}s  "
              f"최대 {max(all_waits) if all_waits else float('nan'):.1f}s  평균 {statistics.mean(all_waits) if all_waits else float('nan'):.1f}s")
    finally:
        if created and not args.keep:
            for vid in created:
                api.call("DELETE", f"/api/v1/visits/{vid}", auth=True)
            print(f"점검용 접수 {len(created)}건을 취소했다")

    if problems:
        print(f"\n❌ 문제 {len(problems)}건")
        return 1
    print("\n✅ 섞임·오류 없음")
    return 0


if __name__ == "__main__":
    sys.exit(main())
