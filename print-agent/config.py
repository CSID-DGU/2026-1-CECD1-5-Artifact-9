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

# ── 인증 ───────────────────────────────────────────────────────────────────
# 비워두면 인증을 걸지 않는다 — 맥북 안에서만 도는 기본 운영 형태다.
#
# Cloudflare Tunnel 등으로 이 에이전트를 공개 HTTPS 주소에 노출하는 순간
# 반드시 채워야 한다. 노출된 상태에서 토큰이 없으면 주소를 아는 누구나 병원
# 프린터로 종이를 뽑을 수 있다. 백엔드는 이 값을 PRINT_AGENT_TOKEN 으로 받아
# Authorization: Bearer 헤더에 실어 보낸다(README "터널로 공개하기" 참고).
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
# 템플릿째로 환경변수로 뺀다. {base} 는 PORTAL_BASE_URL 로 치환된다.
VISIT_SUMMARY_URL_TEMPLATE = os.getenv(
    "VISIT_SUMMARY_URL_TEMPLATE", "{base}/main/lookup?visitId={visitId}"
)
CERTIFICATE_VERIFY_URL_TEMPLATE = os.getenv(
    "CERTIFICATE_VERIFY_URL_TEMPLATE", "{base}/main/certificate?serialNo={serialNo}"
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
