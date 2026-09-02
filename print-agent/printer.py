"""ESC/POS 저수준 헬퍼 — 실측으로 확인된 동작만 담는다.

이 파일에 있는 것들은 전부 SEWOO SLK-TS100 (80mm, 203dpi) 실물에서 확인한
동작이다. 추측으로 바꾸지 말 것. 특히 한글 출력은 p.text() 로는 물음표(?)만
나오고, FS & / FS . 로 2바이트 문자 모드를 직접 열고 닫아야 한다.
"""

import io
import logging
import threading
from contextlib import contextmanager

import config

log = logging.getLogger("print-agent.printer")

# ── ESC/POS 원시 커맨드 ─────────────────────────────────────────────────────
# python-escpos 의 set() 은 버전에 따라 width/height 인자의 의미가 달라진다
# (3.1 부터는 custom_size=True 없이는 무시됨). 버전에 흔들리지 않도록 크기·
# 굵기·정렬은 아래 원시 바이트로 직접 보낸다. 실측 때 p.set(width=2, height=2,
# bold=True) 로 나갔던 것과 같은 바이트다.
ESC_INIT = b"\x1b\x40"          # ESC @  : 버퍼/스타일 초기화
ESC_ALIGN = b"\x1b\x61"         # ESC a n: 0=좌 1=중앙 2=우
ESC_BOLD = b"\x1b\x45"          # ESC E n: 0=해제 1=굵게
GS_SIZE = b"\x1d\x21"           # GS ! n : 상위 4비트=가로배율-1, 하위 4비트=세로배율-1
FS_2BYTE_ON = b"\x1c\x26"       # FS &   : 2바이트 문자 모드 ON
FS_2BYTE_OFF = b"\x1c\x2e"      # FS .   : 2바이트 문자 모드 OFF

_ALIGN = {"left": b"\x00", "center": b"\x01", "right": b"\x02"}

# 프린터는 물리적으로 한 대뿐이라 동시에 두 문서를 밀어넣으면 줄이 섞인다.
_print_lock = threading.Lock()

_profile_cache = None


def _profile():
    """인쇄 폭을 알려주는 프로파일.

    python-escpos 의 기본 프로파일은 media.width.pixel 이 "Unknown" 이라
    image(center=True) 가 조용히 무시된다("The media.width.pixel field of the
    printer profile is not set"). 80mm/203dpi = 576도트를 직접 채워 넣어야
    도장 이미지가 가운데로 온다.

    참고: python-escpos 3.1 의 get_profile() 은 capabilities.Profile 의
    하위 클래스만 인스턴스로 받아준다. BaseProfile 을 직접 상속하면
    프로파일 이름으로 오해해서 KeyError 가 난다.
    """
    global _profile_cache
    if _profile_cache is None:
        import copy

        from escpos import capabilities

        data = copy.deepcopy(capabilities.get_profile("default").profile_data)
        data.setdefault("media", {})["width"] = {
            "pixels": config.PAPER_WIDTH_DOTS,
            "mm": 80,
        }

        class ArtifactProfile(capabilities.Profile):
            profile_data = data

            def __init__(self) -> None:
                # Profile.__init__ 이 features 를 빈 dict 로 덮어써서
                # cut() 같은 기능 판정이 깨진다. 원본 값을 넘겨준다.
                super().__init__(features=data.get("features", {}))

        _profile_cache = ArtifactProfile()
    return _profile_cache

# 도장 이미지는 매번 SVG 를 래스터화할 필요가 없어 한 번만 만들어 재사용한다.
_seal_cache: dict[int, object] = {}


class PrinterError(RuntimeError):
    """프린터를 못 찾았거나 출력 도중 실패했을 때."""


# ── 연결 ───────────────────────────────────────────────────────────────────
@contextmanager
def open_printer():
    """USB 프린터를 열고 문서 하나를 출력한 뒤 닫는다.

    macOS 14 부터 CUPS raw 대기열(lpadmin -m raw)이 폐기되어 쓸 수 없으므로
    pyusb 직접 접근만 사용한다. sudo 는 필요 없다.

    연결을 계속 붙들고 있지 않고 요청마다 여닫는 이유: 프린터를 뽑았다 꽂아도
    다음 요청에서 알아서 복구되기 때문이다.
    """
    from escpos.printer import Dummy, Usb

    with _print_lock:
        if config.DRY_RUN:
            # 프린터 없이 레이아웃/한글 인코딩만 확인하는 모드.
            dummy = Dummy(profile=_profile())
            yield dummy
            log.info("DRY_RUN — 실제 출력 없음 (%d bytes)", len(dummy.output))
            return

        # Usb() 생성자는 장치를 찾지 않는다. python-escpos 3.1 은 첫 쓰기 때
        # device 프로퍼티가 lazy open 하므로, 여기서 open() 을 명시적으로 불러
        # "프린터가 없다"를 문서 조립 전에 확정한다. 그러지 않으면 escpos 의
        # DeviceNotFoundError 가 출력 도중에 튀어나와 500 으로 새어 나간다.
        try:
            p = Usb(
                config.PRINTER_VID,
                config.PRINTER_PID,
                timeout=config.PRINTER_TIMEOUT,
                profile=_profile(),
            )
            p.open()
        except Exception as exc:  # DeviceNotFoundError, NoBackendError, USBError 등
            raise PrinterError(
                f"프린터를 열지 못했습니다 (VID={config.PRINTER_VID:#06x}, "
                f"PID={config.PRINTER_PID:#06x}). 전원/USB 케이블과 libusb 설치를 확인하세요: {exc}"
            ) from exc
        try:
            yield p
        except PrinterError:
            raise
        except Exception as exc:
            # 출력 도중 케이블이 빠지거나 용지가 걸린 경우도 프린터 문제로 본다.
            # 백엔드/프론트가 503 을 보고 "프린터 문제"라고 안내할 수 있어야 한다.
            raise PrinterError(f"출력 중 프린터 통신이 끊겼습니다: {exc}") from exc
        finally:
            try:
                p.close()
            except Exception:  # 닫기 실패는 출력 결과에 영향이 없다
                log.debug("프린터 닫기 실패", exc_info=True)


def reset(p) -> None:
    """문서 시작마다 호출. 안 하면 직전 문서의 잔여 텍스트가 다시 찍힌다."""
    p._raw(ESC_INIT)


# ── 스타일 ─────────────────────────────────────────────────────────────────
def align(p, mode: str = "left") -> None:
    p._raw(ESC_ALIGN + _ALIGN.get(mode, _ALIGN["left"]))


def bold(p, on: bool = True) -> None:
    p._raw(ESC_BOLD + (b"\x01" if on else b"\x00"))


def size(p, width: int = 1, height: int = 1) -> None:
    """문자 배율. width/height 는 1~8. (1,1) 이 기본 크기."""
    width = max(1, min(8, width))
    height = max(1, min(8, height))
    p._raw(GS_SIZE + bytes([((width - 1) << 4) | (height - 1)]))


def style(p, *, align_mode: str = "left", is_bold: bool = False, w: int = 1, h: int = 1) -> None:
    align(p, align_mode)
    bold(p, is_bold)
    size(p, w, h)


def reset_style(p) -> None:
    style(p, align_mode="left", is_bold=False, w=1, h=1)


# ── 텍스트 ─────────────────────────────────────────────────────────────────
def kr(p, s: str) -> None:
    """한글이 섞인 문자열을 출력한다. 줄바꿈은 붙이지 않는다.

    p.text() 를 쓰면 한글이 전부 '?' 로 나온다 — python-escpos 의 magic_encode
    가 이 프린터의 한글 코드페이지를 못 잡기 때문이다. FS & 로 2바이트 문자
    모드를 켜고 euc-kr 로 인코딩해 밀어넣은 뒤 FS . 로 닫는 것이 유일하게
    확인된 방법이다. 한글/영문이 섞인 문자열을 통째로 넣어도 정상 출력된다.
    """
    p._raw(FS_2BYTE_ON)
    p._raw(s.encode("euc-kr", errors="replace"))
    p._raw(FS_2BYTE_OFF)


def kr_line(p, s: str = "") -> None:
    """한 줄 출력 + 줄바꿈."""
    if s:
        kr(p, s)
    p._raw(b"\n")


def feed(p, lines: int = 1) -> None:
    p._raw(b"\n" * max(0, lines))


def divider(p, char: str = "-") -> None:
    """구분선. Font A 기준 한 줄이 42칸이라 42개를 찍는다."""
    p._raw((char * config.LINE_WIDTH).encode("ascii", errors="replace"))
    p._raw(b"\n")


# ── 줄바꿈 계산 ─────────────────────────────────────────────────────────────
def display_width(s: str) -> int:
    """감열지에서 차지하는 칸 수. 한글 등 2바이트 문자는 2칸, ASCII 는 1칸."""
    return sum(2 if ord(ch) > 0x7F else 1 for ch in s)


def wrap(text: str, width: int | None = None, indent: str = "  ") -> list[str]:
    """폭에 맞춰 줄을 나눈다. 두 번째 줄부터는 indent 만큼 들여쓴다.

    한글 21자(=42칸) 기준. 긴 약품명이 두 줄로 넘어갈 때 이어지는 줄이
    항목 시작처럼 보이지 않도록 들여쓰기를 유지한다.

    한 단어가 한 줄보다 길면(공백 없는 긴 약품명) 현재 줄의 남은 칸부터
    채우고 글자 단위로 쪼갠다 — "- " 만 남고 다음 줄로 넘어가지 않도록.
    """
    width = width or config.LINE_WIDTH
    if not text:
        return [""]

    indent_w = display_width(indent)
    lines: list[str] = []
    cur = ""
    limit = width

    def flush() -> None:
        nonlocal cur, limit
        lines.append(cur.rstrip())
        cur = ""
        limit = width - indent_w  # 이어지는 줄은 들여쓰기만큼 좁아진다

    for token in _tokens(text):
        while token:
            space = limit - display_width(cur)
            if display_width(token) <= space:
                cur += token
                break
            if space < 2:  # 남은 칸이 한 글자도 안 되면 줄부터 넘긴다
                flush()
                token = token.lstrip()
                continue
            if display_width(token.rstrip()) <= limit:
                flush()  # 다음 줄에 통째로 들어가는 단어 → 평범한 단어 넘김
                token = token.lstrip()
                continue
            cut = _cut_at(token, space)  # 단어 자체가 한 줄보다 길다
            cur += token[:cut]
            token = token[cut:]
            flush()

    if cur.strip() or not lines:
        lines.append(cur.rstrip())

    return [lines[0]] + [indent + ln for ln in lines[1:]]


def _tokens(text: str) -> list[str]:
    """공백을 뒤에 붙인 단어 단위로 자른다."""
    out: list[str] = []
    buf = ""
    for ch in text:
        buf += ch
        if ch == " ":
            out.append(buf)
            buf = ""
    if buf:
        out.append(buf)
    return out


def _cut_at(s: str, limit: int) -> int:
    used = 0
    for i, ch in enumerate(s):
        w = 2 if ord(ch) > 0x7F else 1
        if used + w > limit:
            return max(1, i)
        used += w
    return len(s)


def kr_wrapped(p, text: str, indent: str = "  ") -> None:
    """긴 문장을 폭에 맞춰 접어서 출력한다."""
    for line in wrap(text, indent=indent):
        kr_line(p, line)


def label_value(p, label: str, value: str) -> None:
    """'환자명 : 홍길동' 형태의 한 항목. 값이 길면 접힌다."""
    kr_wrapped(p, f"{label} : {value}", indent=" " * (len(label) + 3))


# ── QR ─────────────────────────────────────────────────────────────────────
def render_qr(p, data: str, qr_size: int | None = None) -> dict:
    """QR 하나를 가운데 정렬로 출력한다.

    개인정보(이름·생년월일 등)는 절대 여기 들어가지 않는다. 토큰이 포함된
    URL 만 넣는다 — 종이를 주웠다고 해서 환자 정보를 알 수 있으면 안 된다.

    native=True 면 프린터 펌웨어가 그리지만, 데이터가 길면 펌웨어가 거부할 수
    있다. 그 경우 비트맵 렌더링으로 자동 폴백한다.
    """
    qr_size = config.QR_SIZE if qr_size is None else qr_size
    align(p, "center")
    used_native = config.QR_NATIVE
    try:
        if config.QR_NATIVE:
            # native QR 은 python-escpos 가 center 를 지원하지 않는다
            # ("Centering not implemented for native QR rendering").
            # 위에서 ESC a 로 가운데 정렬을 걸어뒀으므로 center=False 로 둔다.
            p.qr(data, ec=config.QR_EC_LEVEL, size=qr_size, native=True, center=False)
        else:
            p.qr(data, ec=config.QR_EC_LEVEL, size=qr_size, native=False, center=True)
    except Exception as exc:
        if not config.QR_NATIVE:
            align(p, "left")
            raise PrinterError(f"QR 출력에 실패했습니다: {exc}") from exc
        log.warning("native QR 실패 — 비트맵으로 폴백합니다 (size=%s): %s", qr_size, exc)
        used_native = False
        try:
            p.qr(data, ec=config.QR_EC_LEVEL, size=qr_size, native=False, center=True)
        except Exception as exc2:
            align(p, "left")
            raise PrinterError(f"QR 출력에 실패했습니다(폴백 포함): {exc2}") from exc2
    align(p, "left")
    return {"qrSize": qr_size, "qrNative": used_native}


# ── 도장 ───────────────────────────────────────────────────────────────────
def _load_seal(px: int):
    """hospital-seal.svg 를 1비트 흑백 이미지로 만든다.

    감열지는 흑백 2치 출력만 되므로 회색 계조를 임계값으로 잘라낸다.
    SVG 의 투명 배경을 흰색으로 깔지 않으면 배경 전체가 검게 찍힌다.
    """
    if px in _seal_cache:
        return _seal_cache[px]

    import cairosvg
    from PIL import Image

    png_bytes = cairosvg.svg2png(
        url=config.SEAL_SVG_PATH, output_width=px, output_height=px
    )
    img = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    bg = Image.new("RGBA", img.size, "WHITE")
    bg.paste(img, (0, 0), img)  # 투명 배경 → 흰색
    mono = bg.convert("L").point(lambda x: 0 if x < 200 else 255, "1")
    _seal_cache[px] = mono
    return mono


def render_seal(p, px: int | None = None) -> None:
    px = config.SEAL_SIZE_PX if px is None else px
    try:
        img = _load_seal(px)
    except Exception as exc:
        raise PrinterError(
            f"도장 이미지를 만들지 못했습니다 ({config.SEAL_SVG_PATH}): {exc}"
        ) from exc
    align(p, "center")
    try:
        p.image(img, center=True)
    except TypeError:  # center 인자를 지원하지 않는 구버전
        p.image(img)
    align(p, "left")


def cut(p) -> None:
    p.cut()
