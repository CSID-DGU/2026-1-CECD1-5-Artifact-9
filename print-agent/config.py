"""print-agent 설정 — 전부 환경변수로 뺀다.

여기 있는 값 중 QR_SIZE 와 KIOSK_BASE_URL 은 절대 코드에 박지 않는다.
QR 크기는 아이패드 카메라로 실측이 끝나야 확정되는 값이고(README 의
"QR 크기 캘리브레이션" 참고), base URL 은 배포 도메인이 바뀌면 같이 바뀐다.
"""

import os
from pathlib import Path

from dotenv import load_dotenv

# print-agent/.env 를 읽는다. 파일이 없으면 조용히 넘어가고 OS 환경변수만 쓴다.
load_dotenv(Path(__file__).parent / ".env")


def _int(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw, 0)  # 0x1fc9 같은 16진수 표기도 그대로 먹힌다
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "y", "on")


# ── 키오스크 ────────────────────────────────────────────────────────────────
# QR 에는 토큰이 아니라 "전체 URL" 을 넣는다. 키오스크 기기가 유선 안드로이드에서
# 아이패드로 바뀌었고, 아이패드 기본 카메라는 QR 내용이 완전한 URL 일 때만
# 사파리로 이동시켜 주기 때문이다. HID 스캐너로 읽어도 프론트의 extractToken()
# 이 /kiosk/{token} 경로를 파싱하므로 같은 QR 하나로 둘 다 커버된다.
KIOSK_BASE_URL = os.getenv("KIOSK_BASE_URL", "https://artifact-prod.duckdns.org").rstrip("/")

# 접수 화면이 요청 본문으로 base URL 을 보내오면 그 값을 우선한다(build_ticket 참고).
# 화면에 뜬 QR 과 종이에 찍힌 QR 이 서로 다른 주소를 가리키면 환자가 엉뚱한 곳으로
# 이동하기 때문이다. 여기 값은 그 값이 없을 때 쓰는 기본값이다.

# ── 백엔드 폴링 ─────────────────────────────────────────────────────────────
# 이 에이전트는 백엔드가 불러 주기를 기다리지 않고, 자기가 백엔드에 접속해
# "뽑을 것 있나" 를 물으러 간다(poller.py). 그래야 접수 데스크 맥북에 고정 주소나
# 터널이 없어도, 그리고 서버에 아무 설정을 넣지 않아도 원격 배포본의 접수증이
# 이 프린터로 나온다.
#
# 기본값이 운영 배포 주소인 이유: 이 프로세스가 도는 곳은 접수 데스크 맥북 한 대뿐이고
# 거기서 부를 백엔드는 운영본이다. 로컬 백엔드로 붙여 시험할 때만 .env 에서 바꾼다.
BACKEND_URL = os.getenv("BACKEND_URL", "https://artifact-prod.duckdns.org").rstrip("/")

# 폴링에 쓸 직원 계정. 사람이 쓰는 계정을 같이 쓰지 않는다 — 비밀번호를 이 파일에
# 적어 두게 되고, 누가 무엇을 했는지 감사 로그에서 구분되지 않기 때문이다.
# 프린터 전용 계정을 하나 만들어 여기에만 둔다. .env 는 gitignore 대상이다.
BACKEND_LOGIN_ID = os.getenv("BACKEND_LOGIN_ID", "").strip()
BACKEND_PASSWORD = os.getenv("BACKEND_PASSWORD", "")

# 폴링을 끄는 스위치. 프린터 레이아웃만 손볼 때처럼 백엔드 없이 띄우고 싶을 때 쓴다.
POLL_ENABLED = _bool("POLL_ENABLED", True)

# 롱 폴링 한 번을 기다리는 시간. 백엔드의 print.queue.poll-wait-seconds(기본 25초)
# 보다 넉넉해야 한다 — 더 짧으면 서버가 막 건네려던 작업을 못 받고 끊는다.
POLL_TIMEOUT_SECONDS = _int("POLL_TIMEOUT_SECONDS", 40)

# ── 인증 ───────────────────────────────────────────────────────────────────
# 이 에이전트의 HTTP 엔드포인트(/print/*, /health)를 지키는 토큰.
#
# 비워두면 인증을 걸지 않는다. 폴링 구조에서는 백엔드가 이쪽 엔드포인트를 부르지
# 않으므로 평소에는 비워 둬도 된다 — 인쇄는 전부 poller.py 가 가져와서 처리한다.
#
# 다만 AGENT_HOST 기본값이 0.0.0.0 이라 같은 와이파이의 다른 기기는 닿을 수 있다.
# 공용 와이파이를 쓰는 자리라면 AGENT_HOST=127.0.0.1 로 묶거나 이 토큰을 채운다.
# PRINT_MODE=direct 로 되돌려 백엔드가 직접 부르게 한다면, 백엔드의
# PRINT_AGENT_TOKEN 에 같은 값을 넣어야 401 이 나지 않는다.
AGENT_TOKEN = os.getenv("AGENT_TOKEN", "").strip()

# ── QR ─────────────────────────────────────────────────────────────────────
# QR_SIZE 는 아직 실측 전 잠정값이다. 지금까지 검증된 조합은 8자리 토큰 + size=6
# 뿐이고, 지금은 48자 안팎의 URL 을 넣기 때문에 모듈 수가 늘어난다.
# 감열지에서 아이패드가 실제로 읽는 최소 크기는 /debug/qr-calibration 으로
# 뽑아보고 정해야 한다.
QR_SIZE = _int("QR_SIZE", 8)

# native=True 면 프린터 펌웨어가 QR 을 그린다(빠르고 선명). 데이터가 길거나
# 펌웨어가 거부하면 실패할 수 있어서, 실패 시 비트맵 렌더링으로 자동 폴백한다.
QR_NATIVE = _bool("QR_NATIVE", True)

# ── 도장 이미지 ─────────────────────────────────────────────────────────────
# frontend/public/hospital-seal.svg 를 그대로 재사용한다. A4 증명서와 감열지
# 확인증에 같은 도장이 찍혀야 하므로 원본을 하나만 둔다.
SEAL_SVG_PATH = os.getenv(
    "SEAL_SVG_PATH",
    str(Path(__file__).resolve().parent.parent / "frontend" / "public" / "hospital-seal.svg"),
)
# 200px ≈ 25mm. 200/160 둘 다 육안 판독 가능한 것을 확인했고 기본값은 200.
SEAL_SIZE_PX = _int("SEAL_SIZE_PX", 200)

# ── 프린터 ─────────────────────────────────────────────────────────────────
# SEWOO SLK-TS100 (80mm, 203dpi). USB 문자열은 manufacturer="POS",
# product="POS Receipt Printer" 로 잡힌다.
#
# CUPS raw 대기열(lpadmin -m raw)은 macOS 14 부터 폐기되어 쓸 수 없다
# ("원본 대기열이 macOS에서 더 이상 지원되지 않습니다"). 그래서 pyusb 직접
# 접근만 사용한다. sudo 없이 동작한다.
PRINTER_VID = _int("PRINTER_VID", 0x1FC9)
PRINTER_PID = _int("PRINTER_PID", 0x2016)
PRINTER_TIMEOUT = _int("PRINTER_TIMEOUT", 0)

# ── 용지 ───────────────────────────────────────────────────────────────────
# Font A 기준 한 줄 42칸. 한글은 2칸을 먹으므로 한글만이면 21자.
LINE_WIDTH = _int("LINE_WIDTH", 42)

# ── 서버 ───────────────────────────────────────────────────────────────────
# 5000 은 macOS 의 AirPlay 수신(ControlCenter)이, 5001 은 이 맥북에서 도는
# 다른 프로젝트(lootmap-osrm 컨테이너)가 이미 잡고 있다. 그래서 5051 을 쓴다.
# 바꾸려면 백엔드의 PRINT_AGENT_URL 도 같이 바꿔야 한다.
AGENT_PORT = _int("AGENT_PORT", 5051)
AGENT_HOST = os.getenv("AGENT_HOST", "0.0.0.0")

# 진위확인/조회 링크의 기준 주소. 보통 키오스크와 같은 도메인이다.
PORTAL_BASE_URL = os.getenv("PORTAL_BASE_URL", KIOSK_BASE_URL).rstrip("/")

HOSPITAL_NAME = os.getenv("HOSPITAL_NAME", "아티팩트 피부과의원")

# 진료요약서 / 발급확인증 QR 이 가리킬 주소. 프론트 라우트가 바뀔 수 있어
# 템플릿째로 환경변수로 뺀다. {base} 는 PORTAL_BASE_URL, {token} 은 백엔드가
# 발급한 문서별 열람 토큰으로 치환된다.
#
# 경로가 /main/... 이 아니라 /d/... 인 이유가 둘 있다.
#   1. /main/* 은 로그인이 필요한 화면이다. QR 을 찍는 사람은 병원 계정이 없는
#      환자라 예전 주소로는 로그인 화면만 봤다.
#   2. QR 은 담는 글자가 늘수록 모듈이 촘촘해져 감열지에서 읽기 어려워진다.
#      QR_SIZE 실측이 아직 안 끝난 상태라, 주소를 짧게 잡아 여유를 남긴다.
VISIT_SUMMARY_URL_TEMPLATE = os.getenv(
    "VISIT_SUMMARY_URL_TEMPLATE", "{base}/d/v/{token}"
)
CERTIFICATE_VERIFY_URL_TEMPLATE = os.getenv(
    "CERTIFICATE_VERIFY_URL_TEMPLATE", "{base}/d/c/{token}"
)

# QR 오류정정 레벨: L(0) M(1) Q(2) H(3). 레벨을 올리면 조금 긁혀도 읽히지만
# 모듈 수가 늘어 같은 QR_SIZE 에서 더 커진다. 지금까지 검증된 값은 L.
QR_EC_LEVEL = _int("QR_EC_LEVEL", 0)

# 프린터 없이 엔드포인트만 확인하고 싶을 때. true 면 실제 USB 로 보내지 않고
# escpos.printer.Dummy 로 바이트만 만들어 본다(레이아웃/한글 인코딩 검증용).
DRY_RUN = _bool("DRY_RUN", False)

# 80mm / 203dpi 프린터의 인쇄 가능 폭(도트). python-escpos 가 QR·도장 이미지를
# 가운데로 밀어줄 때 이 값을 쓴다. 기본 프로파일에는 폭 정보가 없어서 직접 준다.
PAPER_WIDTH_DOTS = _int("PAPER_WIDTH_DOTS", 576)
