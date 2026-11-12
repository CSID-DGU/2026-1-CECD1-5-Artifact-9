"""
부스 시나리오 점검 — 운영진 이름의 환자 3명으로 부스 동선 전체를 **실제 서버**에서 돌려 본다.

부스 운영 방식
--------------
방문객의 개인정보는 받지 않는다. 운영진 이름으로 등록한 환자 3명(이권형·김경섭·모수진)을 방문객이 올 때마다 새로
접수해 쓰고, 운영진 한 명이 자기 이름의 환자로 방문객을 맡는다.
  1. 메인 맥북(의사 화면)에서 접수 → 대기번호 접수증 출력
  2. 운영진 폰으로 분석 링크를 열어(지금은 접수증 QR — 텔레그램 알림은 아직 없다) 방문객 사진 촬영 → 예비분석
  3. 차례가 되면 의사 역할 운영진이 결과를 함께 보고 → 진료실 AI 분석·LLM 코멘트 → 처방 → 진료 완료(진료요약 출력)
  4. 증명서 출력
이 스크립트는 그 흐름과, 그 운영 방식에서 생길 수 있는 일을 실제 서버(Caddy → 백엔드 → AI 로드밸런서 → 모델 워커)에
일으키고 결과가 섞이거나 흐름이 끊기는지 본다. 백엔드 규칙 자체는 KioskBoothFlowTest 가 가짜 AI 로 고정한다.

시나리오
--------
  S1 운영진 3명이 동시에 처음부터 끝까지 — 접수 → 링크 확인 화면 → 촬영·예비분석 → 참고 소견 → 의사 화면 → 진료 시작
     → 진료실 사진·AI 분석 → 처방 코멘트 → 처방 → 진료 완료 → 진료확인서 발급 → 링크 닫힘
  S2 같은 링크를 두 폰에서 동시에 — 하나는 409("다른 화면에서 분석 중")
  S3 한 운영진이 방문객 둘을 겹쳐 맡음 — 같은 환자의 접수 둘을 동시에 분석해도 결과가 접수별로 따로
  S4 재촬영하며 모델 바꾸기 — 의사 화면이 새 결과·새 모델로 바뀌고 이전 소견은 지워진다
  S5 사진이 아닌 파일 — 400 + "다시 촬영" 안내, 이전 결과는 그대로
  S6 닫힌 링크 — 틀린 토큰과 취소한 접수의 링크는 404
  S7 인쇄 — 맥북 print-agent 가 끝까지 붙어 있었고 인쇄 대기열이 비었는지(종이는 눈으로 확인)

판정 기준
---------
사진마다 한 번씩 순서대로 분석한 답이 기준이다(kiosk_load_check.py 와 같은 방식). 사진이 다르면 답도 다르므로 다른
사람의 결과가 오면 기준과 어긋나 드러난다. --images 폴더에 서로 다른 사진이 4장 이상 있어야 한다(운영진 3명 + 재촬영
1장). 얼굴 사진이 좋지만, 사진마다 답이 다르기만 하면 된다.

남는 것과 비용
--------------
- 접수 대기로 남은 접수는 끝에 취소한다. S1 의 접수 3건은 '진료 완료'라 취소할 수 없어 운영진 환자의 진료 이력에 남는다
  (접수 메모 "부스 리허설 자동 점검"). 이력을 남기기 싫으면 --kiosk-only 로 의사 화면 확인까지만 돌린다.
- S1 에서 발급한 진료확인서는 끝에 무효 처리한다(사유 "부스 리허설 자동 점검").
- 유료 LLM 호출은 참고 소견 4번(S1 3 + S4 1) + 처방 코멘트 3번이다. --no-llm 이면 부르지 않는다.
- ⚠️ 접수·진료 완료·증명서 발급마다 서버가 감열지 출력을 자동으로 건다. 맥북 print-agent 가 켜져 있으면 실제로 종이가
  나온다(접수증 5장·진료요약 3장·발급확인증 3장). 그래서 에이전트가 붙어 있으면 --allow-print 없이는 시작하지 않는다.
  종이 없이 돌리려면 맥북에서 print-agent 를 잠시 끈다(print-agent/install-launchd.sh uninstall → 끝나면 install).
  서버는 마지막 연결 뒤 90초 동안은 에이전트가 붙어 있다고 보므로 끄고 1분 반쯤 기다렸다가 돌린다. 꺼져 있는 동안
  걸린 출력은 2분 동안 대기열에 남는다 — 끝나고 2분이 지난 뒤에 다시 켠다(그 전에 켜면 밀린 종이가 한꺼번에 나온다).

계정은 의사(DOCTOR) 또는 관리자(ADMIN)여야 한다 — 처방·증명서 무효 처리가 의사 권한이다.
비밀번호는 명령줄이 아니라 환경변수로 받는다(셸 기록에 남지 않게). 이미 발급받은 로그인 토큰(JWT, 24시간 유효)이
있으면 BOOTH_LOGIN_ID / BOOTH_PASSWORD 대신 BOOTH_TOKEN 하나만 줘도 된다.

사용법
------
    export BOOTH_LOGIN_ID=doctor BOOTH_PASSWORD='...'
    python3 scripts/booth/booth_scenarios.py --base-url https://artifact-prod.duckdns.org --images ~/booth-photos

    ... --only S1,S2         # 골라서 (S4·S5 는 기준용 접수를 같이 쓰므로 순서대로 도는 편이 읽기 쉽다)
    ... --no-llm             # 유료 LLM 을 부르지 않는다
    ... --kiosk-only         # S1 을 의사 화면 확인까지만 — 진료 완료 기록이 남지 않는다
    ... --allow-print        # print-agent 가 붙어 있어도 진행한다(종이가 나온다)
    ... --create-patients    # 운영진 환자 3명이 없으면 만든다(기본은 멈춘다 — 접수 화면에서 등록해 두는 것이 원칙)

종료 코드: FAIL 이 하나라도 있으면 1.
"""

from __future__ import annotations  # 맥 기본 python3(3.9)에서도 `X | None` 표기가 돌게

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import threading
import time
import urllib.parse
import uuid
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kiosk_load_check import (  # noqa: E402
    IMAGE_SUFFIXES, OPERATORS, Api, authenticate, find_patients, pct, same_answer, topk)

MEMO = "부스 리허설 자동 점검"
ALL_SCENARIOS = ("S1", "S2", "S3", "S4", "S5", "S6", "S7")
# 사진이 아닌 바이트 — FastAPI 가 열지 못해 invalid_image 400 을 주고, 백엔드가 "다시 촬영" 안내 400 으로 옮긴다.
NOT_A_PHOTO = b"booth rehearsal: this file is not a photo"
# LLM 이 답하지 못했을 때 AiCommentService 가 대신 내보내는 문구들 — 응답은 200 이라 내용으로 가른다.
LLM_FALLBACK_MARKERS = ("생성할 수 없습니다", "일시적으로 혼잡", "생성에 실패")


def parse(data: bytes) -> Any:
    if not data:
        return {}
    try:
        return json.loads(data)
    except ValueError:
        return data[:300].decode(errors="replace")


def short(body: Any) -> str:
    """오류 응답을 한 줄로 — 서버가 준 메시지가 있으면 그것만."""
    if isinstance(body, dict) and body.get("message"):
        return str(body["message"])[:160]
    return str(body)[:160]


def mime_of(path: Path) -> str:
    return mimetypes.guess_type(path.name)[0] or "image/jpeg"


def clinic_topk(analysis: dict) -> list[tuple[str, float]]:
    """진료실 분석 응답(top5)을 키오스크 응답(topK)과 같은 모양으로."""
    return [(t["diseaseCode"], round(float(t["confidence"]), 4)) for t in analysis.get("top5", [])]


class Report:
    ICONS = {"PASS": "✅", "FAIL": "❌", "WARN": "⚠️", "INFO": "ℹ️", "SKIP": "⏭️"}

    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []
        self._lock = threading.Lock()

    def add(self, scenario: str, level: str, message: str) -> None:
        with self._lock:
            self.rows.append((scenario, level, message))
            print(f"  {self.ICONS[level]} {scenario} {message}", flush=True)

    def check(self, scenario: str, ok: bool, good: str, bad: str) -> bool:
        self.add(scenario, "PASS" if ok else "FAIL", good if ok else bad)
        return ok

    def count(self, level: str) -> int:
        return sum(1 for _, lv, _ in self.rows if lv == level)


class Booth:
    def __init__(self, api: Api, report: Report, patients: list[dict], photos: list[Path], task: str, llm: bool):
        self.api, self.r, self.patients, self.photos, self.task, self.llm = api, report, patients, photos, task, llm
        self.alt_task = "skin_lesion" if task == "facial" else "facial"
        self.created: list[int] = []        # 이 실행이 만든 접수 — 끝에 접수 대기면 취소한다
        self.completed: list[int] = []      # 진료 완료까지 간 접수 — 진료요약이 출력된다
        self.certificates: list[int] = []   # 끝에 무효 처리한다
        self.steps: dict[str, list[float]] = defaultdict(list)
        self.baseline: dict[tuple[str, str], list[tuple[str, float]]] = {}
        self.kcd: dict | None = None
        self.drug: dict | None = None
        self.scratch: dict | None = None    # 기준·S2~S6 이 같이 쓰는 접수(운영진 첫 번째 환자)
        self.second: dict | None = None     # S3 의 두 번째 접수(같은 환자)
        self._lock = threading.Lock()

    # ── 요청 ─────────────────────────────────────────────────
    # 예외를 던지지 않는다. 시나리오 하나가 실패해도 나머지가 끝까지 돌아야 보고서가 쓸모 있다.

    def call(self, method: str, path: str, payload: Any = None, auth: bool = True) -> tuple[int, Any, float]:
        body = json.dumps(payload).encode() if payload is not None else None
        try:
            status, data, took = self.api.call(method, path, body, "application/json" if body is not None else None, auth)
        except OSError as e:  # 연결 실패·시간 초과(urllib 의 URLError 도 OSError 다)
            return 0, f"연결 실패: {e}", 0.0
        return status, parse(data), took

    def upload(self, path: str, fields: dict[str, str], filename: str, data: bytes, mime: str,
               auth: bool) -> tuple[int, Any, float]:
        boundary = uuid.uuid4().hex
        chunks = [f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                  for k, v in fields.items()]
        chunks.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                      f"Content-Type: {mime}\r\n\r\n".encode() + data + b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode())
        try:
            status, raw, took = self.api.call("POST", path, b"".join(chunks),
                                              f"multipart/form-data; boundary={boundary}", auth)
        except OSError as e:
            return 0, f"연결 실패: {e}", 0.0
        return status, parse(raw), took

    def analyze(self, token: str, photo: Path, task: str) -> tuple[int, Any, float]:
        return self.upload(f"/api/kiosk/session/{token}/analyze", {"analysisType": task}, photo.name,
                           photo.read_bytes(), mime_of(photo), auth=False)

    def timed(self, step: str, took: float) -> None:
        with self._lock:
            self.steps[step].append(took)

    def receive(self, patient: dict) -> dict | None:
        status, body, took = self.call("POST", "/api/v1/visits", {"patientId": patient["id"], "receptionMemo": MEMO})
        if status != 201 or not isinstance(body, dict):
            return None
        with self._lock:
            self.created.append(body["id"])
        self.timed("접수", took)
        return body

    def preliminary(self, visit_id: int) -> tuple[int, Any]:
        status, body, took = self.call("GET", f"/api/v1/visits/{visit_id}/preliminary")
        self.timed("의사 화면 조회", took)
        return status, body

    def same_heatmap(self, tablet_url: str | None, doctor_url: str | None) -> bool | None:
        if not tablet_url or not doctor_url:
            return None
        try:
            s1, a, _ = self.api.call("GET", tablet_url)
            s2, b, _ = self.api.call("GET", doctor_url, auth=True)
        except OSError:
            return False
        return s1 == 200 and s2 == 200 and hashlib.sha256(a).digest() == hashlib.sha256(b).digest()

    # ── 준비 ─────────────────────────────────────────────────

    def prepare(self) -> bool:
        """기준 답을 만든다. 기준용 접수는 운영진 첫 번째 환자로 만들고, S2~S6 이 이어서 쓴다."""
        print(f"\n[준비] 사진마다 한 번씩 순서대로 분석해 기준 답을 만든다 (접수 1건, {self.patients[0]['name']})")
        self.scratch = self.receive(self.patients[0])
        if self.scratch is None:
            self.r.add("준비", "FAIL", "기준용 접수를 만들 수 없다")
            return False
        plan = [(p, self.task) for p in self.photos[:len(self.patients)]] + [(self.photos[len(self.patients)], self.alt_task)]
        for photo, task in plan:
            status, body, took = self.analyze(self.scratch["kioskToken"], photo, task)
            if status != 200 or not isinstance(body, dict):
                self.r.add("준비", "FAIL", f"기준 분석 실패 {photo.name} {task}: HTTP {status} {short(body)}")
                return False
            self.baseline[(photo.name, task)] = topk(body)
            print(f"    {task:11s} {photo.name:36.36s} {self.baseline[(photo.name, task)][0]}  {took:4.1f}s")
        answers = [self.baseline[(p.name, self.task)] for p in self.photos[:len(self.patients)]]
        if any(same_answer(a, b) for i, a in enumerate(answers) for b in answers[i + 1:]):
            self.r.add("준비", "WARN", "운영진 사진 중 답이 똑같은 두 장이 있다 — 그 둘이 서로 바뀌어도 알아볼 수 없다. 사진을 바꾼다")
        return True

    # ── S1 ──────────────────────────────────────────────────

    def s1(self, kiosk_only: bool) -> None:
        print(f"\n[S1] 운영진 {len(self.patients)}명이 동시에 처음부터 끝까지" + (" (의사 화면 확인까지)" if kiosk_only else ""))
        barrier = threading.Barrier(len(self.patients))

        def guarded(i: int) -> None:
            try:
                self._s1_one(i, barrier, kiosk_only)
            except Exception as e:  # 한 운영진의 예상 못한 오류가 다른 두 명의 점검까지 멈추지 않게
                self.r.add("S1", "FAIL", f"{self.patients[i]['name']}: 예상 못한 오류 {type(e).__name__}: {e}")

        with ThreadPoolExecutor(max_workers=len(self.patients)) as pool:
            list(pool.map(guarded, range(len(self.patients))))

    def _s1_one(self, i: int, barrier: threading.Barrier, kiosk_only: bool) -> None:
        sc, patient, photo = "S1", self.patients[i], self.photos[i]
        who = patient["name"]
        want = self.baseline[(photo.name, self.task)]
        barrier.wait()  # 세 운영진이 같은 순간에 접수부터 시작한다

        visit = self.receive(patient)
        if visit is None:
            self.r.add(sc, "FAIL", f"{who}: 접수 실패")
            return
        vid, token = visit["id"], visit["kioskToken"]
        number = f"V{vid:05d}"
        self.r.add(sc, "PASS", f"{who}: 접수 {number}")

        # 운영진 폰이 링크를 연다 — 본인 확인 화면의 이름·접수번호
        status, session, took = self.call("GET", f"/api/kiosk/session/{token}", auth=False)
        self.timed("링크 열기", took)
        self.r.check(sc, status == 200 and isinstance(session, dict) and session.get("patientName") == who
                     and session.get("receptionNumber") == number and session.get("analyzed") is False,
                     f"{who}: 링크 확인 화면 — {who}·{number}",
                     f"{who}: 링크 확인 화면이 다르다 HTTP {status} {short(session)}")

        # 촬영·예비분석
        status, result, took = self.analyze(token, photo, self.task)
        self.timed("예비분석", took)
        if status != 200 or not isinstance(result, dict):
            self.r.add(sc, "FAIL", f"{who}: 예비분석 HTTP {status} {short(result)}")
            return
        got = topk(result)
        self.r.check(sc, same_answer(got, want) and result.get("analysisType") == self.task,
                     f"{who}: 예비분석 {got[0][0]} {took:.1f}s — 자기 사진의 답",
                     f"{who}: 예비분석이 자기 사진의 기준과 다르다 got={got[:2]} want={want[:2]} "
                     f"모델={result.get('analysisType')}")

        comment = None
        if self.llm:
            status, body, took = self.call("POST", f"/api/kiosk/session/{token}/comment", auth=False)
            self.timed("참고 소견(LLM)", took)
            comment = body.get("aiComment") if status == 200 and isinstance(body, dict) else None
            if not comment:
                self.r.add(sc, "FAIL", f"{who}: 참고 소견 HTTP {status} {short(body)}")
            elif any(m in comment for m in LLM_FALLBACK_MARKERS):
                self.r.add(sc, "WARN", f"{who}: 참고 소견 대신 안내 문구가 나갔다 — LLM 이 답하지 않았다 ({took:.1f}s)")
            else:
                self.r.add(sc, "PASS", f"{who}: 참고 소견 {took:.1f}s")

        # 메인 맥북(의사 화면)이 같은 결과를 본다
        status, doctor = self.preliminary(vid)
        doctor_ok = (status == 200 and isinstance(doctor, dict) and same_answer(topk(doctor), got)
                     and doctor.get("analysisType") == self.task and (comment is None or doctor.get("aiComment") == comment))
        self.r.check(sc, doctor_ok, f"{who}: 의사 화면이 폰과 같은 결과" + ("·소견" if comment else ""),
                     f"{who}: 의사 화면이 폰과 다르다 HTTP {status} {short(doctor)}")
        heat = self.same_heatmap(result.get("gradcamUrl"), doctor.get("gradcamUrl") if isinstance(doctor, dict) else None)
        if heat is not None:
            self.r.check(sc, heat, f"{who}: 폰과 의사 화면의 히트맵이 같다", f"{who}: 폰과 의사 화면의 히트맵이 다르다")

        if kiosk_only:
            return

        # 차례가 와서 의사 역할 운영진에게 넘어간다
        status, body, took = self.call("PATCH", f"/api/v1/visits/{vid}/start")
        self.timed("진료 시작", took)
        if not self.r.check(sc, status == 200 and isinstance(body, dict) and body.get("status") == "IN_PROGRESS",
                            f"{who}: 진료 시작", f"{who}: 진료 시작 HTTP {status} {short(body)}"):
            return
        status, _, _ = self.call("GET", f"/api/kiosk/session/{token}", auth=False)
        if status == 200:
            self.r.add(sc, "INFO", f"{who}: 진료 중에도 링크가 열려 있다 — 이때 폰으로 다시 찍으면 결과가 바뀌지만, 진료실 "
                                   "카드는 접수 대기일 때만 자동으로 다시 받는다(접수를 다시 눌러야 보인다)")
        else:
            self.r.add(sc, "INFO", f"{who}: 진료 시작 후 링크가 닫혔다 (HTTP {status})")

        status, image, took = self.upload(f"/api/v1/visits/{vid}/images", {}, photo.name, photo.read_bytes(),
                                          mime_of(photo), auth=True)
        self.timed("진료실 사진 업로드", took)
        if not self.r.check(sc, status == 201 and isinstance(image, dict), f"{who}: 진료실 사진 업로드",
                            f"{who}: 진료실 사진 업로드 HTTP {status} {short(image)}"):
            return
        status, analysis, took = self.call("POST", f"/api/v1/visits/{vid}/analysis",
                                           {"imageIds": [image["imageId"]], "analysisType": self.task})
        self.timed("진료실 AI 분석", took)
        if status != 200 or not isinstance(analysis, dict):
            self.r.add(sc, "FAIL", f"{who}: 진료실 AI 분석 HTTP {status} {short(analysis)}")
            return
        clinic = clinic_topk(analysis)
        others = [self.baseline[(p.name, self.task)] for j, p in enumerate(self.photos[:len(self.patients)]) if j != i]
        if same_answer(clinic, want):
            self.r.add(sc, "PASS", f"{who}: 진료실 AI 분석도 같은 사진의 답 {took:.1f}s")
        elif any(same_answer(clinic, other) for other in others):
            self.r.add(sc, "FAIL", f"{who}: 진료실 AI 분석이 다른 운영진 사진의 답이다(섞임) {clinic[:2]}")
        else:
            self.r.add(sc, "WARN", f"{who}: 진료실 AI 분석이 폰 결과와 다르다 {clinic[:2]} vs {want[:2]} — "
                                   "사진 저장 과정에서 내용이 바뀌는지 확인")

        # LLM 처방 코멘트 → 처방 저장
        ai_comment = ai_model = None
        if self.llm:
            status, pc, took = self.call("POST", f"/api/v1/visits/{vid}/prescription/comment", {"diseases": [
                {"kcdCode": self.kcd["code"], "kcdNameKr": self.kcd["nameKr"], "isPrimary": True}]})
            self.timed("처방 코멘트(LLM)", took)
            if status == 200 and isinstance(pc, dict) and pc.get("available"):
                ai_comment = "\n".join(line for line in (pc.get("line1"), pc.get("line2")) if line)
                ai_model = pc.get("model")
                self.r.add(sc, "PASS", f"{who}: 처방 코멘트 {took:.1f}s ({ai_model})")
            elif status == 200 and isinstance(pc, dict):
                self.r.add(sc, "WARN", f"{who}: 처방 코멘트를 못 만들었다 — {short(pc.get('line1'))}")
            else:
                self.r.add(sc, "FAIL", f"{who}: 처방 코멘트 HTTP {status} {short(pc)}")
        status, body, took = self.call("POST", f"/api/v1/visits/{vid}/prescription", {
            "diseases": [{"kcdDiseaseId": self.kcd["id"], "isPrimary": True}],
            "analysisId": analysis.get("analysisId"),
            "doctorNotes": MEMO,
            "aiComment": ai_comment,
            "aiCommentModel": ai_model,
            "details": [{"drugId": self.drug["id"], "medicineName": self.drug["nameKr"],
                         "dosage": "1일 2회 도포", "durationDays": 7}],
        })
        self.timed("처방 저장", took)
        if not self.r.check(sc, status == 200, f"{who}: 처방 저장", f"{who}: 처방 저장 HTTP {status} {short(body)}"):
            return

        # 진료 완료 — 서버가 진료요약 출력을 건다
        status, body, took = self.call("PATCH", f"/api/v1/visits/{vid}/complete")
        self.timed("진료 완료", took)
        if not self.r.check(sc, status == 200 and isinstance(body, dict) and body.get("status") == "COMPLETED",
                            f"{who}: 진료 완료", f"{who}: 진료 완료 HTTP {status} {short(body)}"):
            return
        with self._lock:
            self.completed.append(vid)

        # 증명서 — 지금은 진료확인서로 발급·출력 경로를 본다(부스용 운세 증명서는 아직 없다)
        status, cert, took = self.call("POST", f"/api/v1/visits/{vid}/certificates",
                                       {"type": "TREATMENT_CONFIRMATION", "purpose": "부스 체험"})
        self.timed("증명서 발급", took)
        if status == 200 and isinstance(cert, dict):
            with self._lock:
                self.certificates.append(cert["id"])
            named = (cert.get("content") or {}).get("patientName") == who
            self.r.check(sc, cert.get("visitId") == vid and named,
                         f"{who}: 진료확인서 {cert.get('serialNo')} — 이 접수·이 이름",
                         f"{who}: 진료확인서가 다른 접수나 이름으로 나왔다 visit={cert.get('visitId')}")
        else:
            self.r.add(sc, "FAIL", f"{who}: 증명서 발급 HTTP {status} {short(cert)}")

        # 진료가 끝나면 운영진 폰의 링크는 닫혀야 한다 — 다음 방문객에게 같은 환자를 다시 쓰기 때문
        s1, _, _ = self.call("GET", f"/api/kiosk/session/{token}", auth=False)
        s2, _, _ = self.analyze(token, photo, self.task)
        self.r.check(sc, s1 == 404 and s2 == 404, f"{who}: 진료가 끝난 링크는 닫혔다",
                     f"{who}: 진료가 끝났는데 링크가 열려 있다 (조회 {s1}, 분석 {s2})")

    # ── S2~S7 ───────────────────────────────────────────────

    def s2(self) -> None:
        print("\n[S2] 같은 링크를 두 폰에서 동시에")
        token, photo = self.scratch["kioskToken"], self.photos[0]

        def delayed(delay: float) -> int:
            time.sleep(delay)
            return self.analyze(token, photo, self.task)[0]

        with ThreadPoolExecutor(max_workers=2) as pool:
            codes = sorted(pool.map(delayed, (0.0, 0.3)))
        if codes == [200, 409]:
            self.r.add("S2", "PASS", "200 + 409 — 늦게 누른 폰에는 '다른 화면에서 분석 중' 안내가 간다")
        elif codes == [200, 200]:
            self.r.add("S2", "INFO", "200 + 200 — 첫 분석이 0.3초 안에 끝나 겹치지 않았다(확인 불가, 문제 아님)")
        else:
            self.r.add("S2", "FAIL", f"예상과 다르다: {codes}")

    def s3(self) -> None:
        who = self.patients[0]["name"]
        print(f"\n[S3] 한 운영진({who})이 방문객 둘을 겹쳐 맡음 — 같은 환자의 접수 둘을 동시에 분석")
        self.second = self.receive(self.patients[0])
        if self.second is None:
            self.r.add("S3", "FAIL", "두 번째 접수를 만들 수 없다")
            return
        # 기준 답이 있는 운영진 사진 두 장을 쓴다(운영진이 두 명뿐이어도 된다). 서로 달라야 바뀌면 드러난다.
        pairs = [(self.scratch, self.photos[1]), (self.second, self.photos[0])]
        barrier = threading.Barrier(len(pairs))

        def one(visit: dict, photo: Path) -> tuple[int, Any, float]:
            barrier.wait()
            return self.analyze(visit["kioskToken"], photo, self.task)

        with ThreadPoolExecutor(max_workers=len(pairs)) as pool:
            outcomes = list(pool.map(lambda pair: one(*pair), pairs))
        for (visit, photo), (status, result, took) in zip(pairs, outcomes):
            number = f"V{visit['id']:05d}"
            want = self.baseline[(photo.name, self.task)]
            if status != 200 or not isinstance(result, dict):
                self.r.add("S3", "FAIL", f"{number}: 분석 HTTP {status} {short(result)}")
                continue
            st_doc, doctor = self.preliminary(visit["id"])
            st_ses, session, _ = self.call("GET", f"/api/kiosk/session/{visit['kioskToken']}", auth=False)
            ok = (same_answer(topk(result), want) and st_doc == 200 and same_answer(topk(doctor), want)
                  and st_ses == 200 and isinstance(session, dict)
                  and session.get("patientName") == who and session.get("receptionNumber") == number)
            self.r.check("S3", ok, f"{number}: 폰·의사 화면 모두 자기 사진의 답 ({took:.1f}s), 링크 화면은 {who}·{number}",
                         f"{number}: 섞였다 — 폰 {topk(result)[:1]} 의사 {topk(doctor)[:1] if st_doc == 200 else st_doc} "
                         f"기준 {want[:1]}")

    def s4(self) -> None:
        print(f"\n[S4] 재촬영하며 모델 바꾸기 ({self.task} → {self.alt_task})")
        token, vid = self.scratch["kioskToken"], self.scratch["id"]
        if self.llm:  # 지워져야 할 소견을 먼저 만들어 둔다
            self.call("POST", f"/api/kiosk/session/{token}/comment", auth=False)
        photo = self.photos[len(self.patients)]
        want = self.baseline[(photo.name, self.alt_task)]
        status, result, took = self.analyze(token, photo, self.alt_task)
        if status != 200 or not isinstance(result, dict):
            self.r.add("S4", "FAIL", f"재촬영 HTTP {status} {short(result)}")
            return
        st, doctor = self.preliminary(vid)
        ok = (same_answer(topk(result), want) and result.get("analysisType") == self.alt_task
              and st == 200 and same_answer(topk(doctor), want) and doctor.get("analysisType") == self.alt_task)
        self.r.check("S4", ok, f"새 사진·새 모델({self.alt_task})로 폰과 의사 화면이 바뀌었다 ({took:.1f}s)",
                     f"재촬영이 반영되지 않았다 — 폰 {topk(result)[:1]}/{result.get('analysisType')} "
                     f"의사 {topk(doctor)[:1] if st == 200 else st}")
        if st == 200:
            self.r.check("S4", doctor.get("aiComment") is None,
                         "이전 사진의 참고 소견은 지워졌다" + ("" if self.llm else " (--no-llm: 소견 없이 확인)"),
                         f"이전 사진의 소견이 새 결과 옆에 남아 있다: {str(doctor.get('aiComment'))[:40]}")

    def s5(self) -> None:
        print("\n[S5] 사진이 아닌 파일")
        token, vid = self.scratch["kioskToken"], self.scratch["id"]
        st_before, before = self.preliminary(vid)
        status, body, _ = self.upload(f"/api/kiosk/session/{token}/analyze", {"analysisType": self.task},
                                      "not-a-photo.jpg", NOT_A_PHOTO, "image/jpeg", auth=False)
        text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
        self.r.check("S5", status == 400 and "다시 촬영" in text, "400 + '다시 촬영해 주세요' 안내",
                     f"HTTP {status} {short(body)} — 400 과 다시 촬영 안내여야 한다")
        st_after, after = self.preliminary(vid)
        unchanged = (st_before == st_after == 200 and topk(before) == topk(after)
                     and before.get("analyzedAt") == after.get("analyzedAt"))
        self.r.check("S5", unchanged, "이전 결과는 그대로다", "잘못된 파일이 이전 결과를 바꿨다")

    def s6(self) -> None:
        print("\n[S6] 닫힌 링크")
        status, _, _ = self.call("GET", "/api/kiosk/session/BOOTHNOTREAL", auth=False)
        self.r.check("S6", status == 404, "틀린 토큰 → 404", f"틀린 토큰인데 HTTP {status}")
        for visit in [v for v in (self.second, self.scratch) if v]:
            number = f"V{visit['id']:05d}"
            status, body, _ = self.call("DELETE", f"/api/v1/visits/{visit['id']}")
            if status != 200:
                self.r.add("S6", "FAIL", f"{number}: 접수 취소 HTTP {status} {short(body)}")
                continue
            s1, _, _ = self.call("GET", f"/api/kiosk/session/{visit['kioskToken']}", auth=False)
            s2, _, _ = self.analyze(visit["kioskToken"], self.photos[0], self.task)
            self.r.check("S6", s1 == 404 and s2 == 404, f"{number}: 취소한 접수의 링크는 닫혔다",
                         f"{number}: 취소했는데 링크가 열려 있다 (조회 {s1}, 분석 {s2})")

    def s7(self, connected_at_start: bool) -> None:
        print("\n[S7] 인쇄")
        if not connected_at_start:
            self.r.add("S7", "SKIP", "맥북 print-agent 가 연결돼 있지 않아 이번 실행은 출력하지 않았다 — 부스 당일에는 켜 둔다")
            return
        deadline = time.monotonic() + 60
        while True:
            status, queue, _ = self.call("GET", "/api/v1/print/jobs/status")
            drained = status == 200 and queue.get("pending") == 0 and queue.get("inFlight") == 0
            if drained and queue.get("agentConnected"):
                self.r.add("S7", "PASS", f"에이전트가 끝까지 붙어 있었고 인쇄 대기열이 비었다 — 접수증 {len(self.created)}장·"
                                         f"진료요약 {len(self.completed)}장·발급확인증 {len(self.certificates)}장이 "
                                         "나왔는지 종이로 확인한다")
                return
            if time.monotonic() > deadline:
                self.r.add("S7", "WARN", f"1분이 지나도 정리되지 않았다 — 연결 {queue.get('agentConnected') if status == 200 else status}, "
                                         f"대기 {queue.get('pending') if status == 200 else '?'}. 맥북 print-agent·용지·전원을 확인한다")
                return
            time.sleep(3)

    # ── 뒷정리 ───────────────────────────────────────────────

    def cleanup(self) -> None:
        cancelled = 0
        for vid in self.created:
            status, visit, _ = self.call("GET", f"/api/v1/visits/{vid}")
            if status == 200 and isinstance(visit, dict) and visit.get("status") == "RECEIVED":
                cancelled += self.call("DELETE", f"/api/v1/visits/{vid}")[0] == 200
        voided = sum(self.call("POST", f"/api/v1/certificates/{cid}/void", {"reason": MEMO})[0] == 200
                     for cid in self.certificates)
        print(f"\n[뒷정리] 접수 대기 {cancelled}건 취소 · 진료확인서 {voided}/{len(self.certificates)}건 무효 처리 · "
              f"진료 완료 {len(self.completed)}건은 기록으로 남는다")

    def print_steps(self) -> None:
        if not self.steps:
            return
        print("\n단계별 걸린 시간")
        for step, values in self.steps.items():
            print(f"  {step:14s} 중앙값 {pct(values, .5):5.1f}s  최대 {max(values):5.1f}s  ({len(values)}회)")


def first_item(api: Api, path: str) -> dict | None:
    page = api.json("GET", path)
    items = page.get("content") if isinstance(page, dict) else page
    return items[0] if items else None


def distinct_photos(folder: Path) -> list[Path]:
    """내용이 같은 사진은 하나만 — 같은 사진 두 장은 답도 같아서 섞임을 가려내지 못한다."""
    seen, photos = set(), []
    for path in sorted(p for p in folder.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES):
        digest = hashlib.sha256(path.read_bytes()).digest()
        if digest not in seen:
            seen.add(digest)
            photos.append(path)
    return photos


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--task", choices=["facial", "skin_lesion"], default="facial",
                    help="운영진이 고르는 촬영 대상(부스 기본은 얼굴). S4 는 다른 쪽으로 바꿔 찍는다")
    ap.add_argument("--only", help="쉼표로 구분한 시나리오만 돌린다 (예: S1,S2)")
    ap.add_argument("--no-llm", action="store_true", help="유료 LLM(참고 소견·처방 코멘트)을 부르지 않는다")
    ap.add_argument("--kiosk-only", action="store_true", help="S1 을 의사 화면 확인까지만 — 진료 완료 기록을 남기지 않는다")
    ap.add_argument("--allow-print", action="store_true", help="맥북 print-agent 가 붙어 있어도 진행한다(종이가 나온다)")
    ap.add_argument("--create-patients", action="store_true", help="운영진 환자가 없으면 만든다")
    ap.add_argument("--patients", help="쉼표로 구분한 운영진 환자 이름(2명 이상). 기본은 " + ", ".join(OPERATORS))
    ap.add_argument("--timeout", type=float, default=90.0)
    args = ap.parse_args()

    selected = [s.strip().upper() for s in args.only.split(",")] if args.only else list(ALL_SCENARIOS)
    unknown = [s for s in selected if s not in ALL_SCENARIOS]
    if unknown:
        raise SystemExit(f"모르는 시나리오: {unknown} (가능: {', '.join(ALL_SCENARIOS)})")
    names = [n.strip() for n in args.patients.split(",") if n.strip()] if args.patients else list(OPERATORS)
    if len(names) < 2:
        raise SystemExit("운영진 환자가 2명 이상 필요하다 — 동시에 찍는 상황과 섞임을 보려면 둘은 있어야 한다.")
    photos = distinct_photos(args.images)
    if len(photos) < len(names) + 1:
        raise SystemExit(f"서로 다른 사진이 {len(photos)}장뿐이다. {len(names) + 1}장 이상 필요하다"
                         f"(운영진 {len(names)}명 + 재촬영 1장).")
    api = Api(args.base_url, args.timeout)
    login_id = authenticate(api)
    # 의사 권한인지 먼저 본다 — 직원 계정이면 S1 이 처방 단계에서야 403 으로 멈춰, 접수만 잔뜩 남는다.
    # from·to 를 꼭 붙인다. 빠지면 권한을 보기 전에 400 이 나서 직원 계정도 통과한다.
    today = time.strftime("%Y-%m-%d")
    status, _, _ = api.call("GET", f"/api/v1/prescriptions/doctor-patients?from={today}&to={today}", auth=True)
    if status != 200:
        raise SystemExit(f"의사 권한 확인이 HTTP {status} 다 — 401 이면 토큰 만료·오류, 403 이면 의사·관리자 계정이 "
                         "아니다. DOCTOR 또는 ADMIN 계정으로 다시 준비한다.")

    queue = api.json("GET", "/api/v1/print/jobs/status")
    connected = bool(queue.get("agentConnected"))
    print(f"서버 {args.base_url} · 계정 {login_id} · 촬영 대상 {args.task} · LLM {'끔' if args.no_llm else '켬'} · "
          f"print-agent {'연결됨' if connected else '연결 안 됨'}")
    if connected and not args.allow_print:
        raise SystemExit("맥북 print-agent 가 연결돼 있다 — 이대로 돌리면 접수증·진료요약·발급확인증이 실제로 출력된다.\n"
                         "프린터 점검을 겸하려면 --allow-print 를 주고, 아니면 맥북에서 print-agent 를 잠시 끄고 다시 돌린다\n"
                         "(끄고 1분 반쯤 지나야 '연결 안 됨'으로 바뀐다. 끝난 뒤 2분이 지나서 다시 켠다).")

    patients = find_patients(api, names, create=args.create_patients)
    print("운영진 환자: " + ", ".join(f"{p['name']}(id {p['id']})" for p in patients))

    report = Report()
    booth = Booth(api, report, patients, photos, args.task, llm=not args.no_llm)
    if "S1" in selected and not args.kiosk_only:
        # 여드름(L70)·아다팔렌 겔 — 부스에서 실제로 나올 법한 처방. 없으면 목록의 첫 항목으로 흐름만 본다.
        booth.kcd = first_item(api, "/api/v1/kcd-diseases?query=L70&size=1") or first_item(api, "/api/v1/kcd-diseases?size=1")
        booth.drug = (first_item(api, "/api/v1/drugs?size=1&query=" + urllib.parse.quote("아다팔렌"))
                      or first_item(api, "/api/v1/drugs?size=1"))
        if not booth.kcd or not booth.drug:
            raise SystemExit("상병(KCD)·약품 목록이 비어 있어 처방 단계를 돌릴 수 없다 — --kiosk-only 로 돌리거나 데이터를 확인한다.")
        print(f"처방에 쓸 상병 {booth.kcd['code']} {booth.kcd['nameKr']} · 약품 {booth.drug['nameKr']}")

    try:
        if booth.prepare():
            runners = {"S1": lambda: booth.s1(args.kiosk_only), "S2": booth.s2, "S3": booth.s3, "S4": booth.s4,
                       "S5": booth.s5, "S6": booth.s6, "S7": lambda: booth.s7(connected)}
            for scenario in ALL_SCENARIOS:
                if scenario not in selected:
                    continue
                try:
                    runners[scenario]()
                except Exception as e:  # 한 시나리오의 예상 못한 오류가 나머지 점검을 막지 않게
                    report.add(scenario, "FAIL", f"예상 못한 오류 {type(e).__name__}: {e}")
    finally:
        booth.cleanup()
    booth.print_steps()

    counts = {level: report.count(level) for level in ("PASS", "FAIL", "WARN", "INFO", "SKIP")}
    print("\n" + " · ".join(f"{level} {n}" for level, n in counts.items()))
    if counts["FAIL"]:
        print("\n❌ 실패한 항목")
        for scenario, level, message in report.rows:
            if level == "FAIL":
                print(f"  {scenario} {message}")
        return 1
    print("\n✅ 부스 시나리오 통과" + (" — ⚠️ 항목은 위에서 확인한다" if counts["WARN"] else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
