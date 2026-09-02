"""print-agent — 감열지 POS 프린터 출력 서비스.

맥 호스트에서 직접 실행한다. 도커에 넣지 않는다.

이유: Docker Desktop for Mac 은 USB 패스스루를 지원하지 않아서, 컨테이너 안의
백엔드가 USB 프린터에 접근할 방법이 없다. 그래서 구조가 이렇게 된다.

    [백엔드 컨테이너] --HTTP--> [print-agent (맥 호스트)] --USB--> [프린터]

백엔드는 host.docker.internal:5051 로 이 서비스를 호출한다.
프린터는 접수 데스크 맥북에 물린다. 아이패드 키오스크는 와이파이로 HTTPS
배포본에 붙을 뿐, 프린터와는 아무 관계가 없다.
"""

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.responses import JSONResponse

import auth
import config
import documents
import printer as pr
import schemas

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
)
log = logging.getLogger("print-agent")


@asynccontextmanager
async def lifespan(_: FastAPI):
    """기동 시 인증 상태를 로그에 남긴다.

    AGENT_HOST 기본값이 0.0.0.0 이라 같은 와이파이의 다른 기기도 닿을 수 있고,
    터널을 열면 인터넷 전체가 닿는다. 토큰 없이 그렇게 떠 있는 상태는 조용히
    지나가면 아무도 모르므로 여기서 알린다.
    """
    if config.AGENT_TOKEN:
        log.info("인증 활성화 — Authorization: Bearer 헤더가 있는 요청만 받는다")
    elif config.AGENT_HOST not in ("127.0.0.1", "localhost"):
        log.warning(
            "AGENT_TOKEN 이 비어 있는데 %s 에 바인딩했다. 같은 네트워크의 다른 기기가 "
            "출력을 요청할 수 있다. 터널로 공개할 예정이라면 반드시 AGENT_TOKEN 을 설정할 것.",
            config.AGENT_HOST,
        )
    yield


app = FastAPI(
    title="Artifact print-agent",
    description="감열지 POS 프린터(SEWOO SLK-TS100) 출력 에이전트",
    version="1.0.0",
    lifespan=lifespan,
)

# 인증이 필요한 엔드포인트에 공통으로 건다. AGENT_TOKEN 이 비어 있으면
# require_token 이 그냥 통과시키므로, 맥북 로컬 전용 운영은 지금까지와 똑같다.
PROTECTED = [Depends(auth.require_token)]


@app.get("/ping")
def ping():
    """터널·프로세스 생존 확인 전용. 인증 없이 열려 있지만 아무 정보도 내지 않는다.

    /health 는 프린터 상태와 설정값(키오스크 주소, VID/PID)을 담고 있어서 인증을
    건다. 터널 상태만 확인하고 싶을 때 쓸 창구가 따로 필요해 이 엔드포인트를 둔다.
    """
    return {"ok": True}


def _fail(doc_type: str, exc: Exception, status: int) -> JSONResponse:
    """실패는 항상 같은 모양의 JSON 으로 돌려준다.

    백엔드는 이 실패를 log.warn 만 남기고 접수/발급 흐름은 그대로 진행한다.
    프린터가 꺼져 있다고 접수가 막히면 안 되기 때문이다.
    """
    log.warning("출력 실패 [%s] %s", doc_type, exc)
    return JSONResponse(
        status_code=status,
        content={"ok": False, "docType": doc_type, "detail": str(exc)},
    )


def _print(doc_type: str, payload) -> JSONResponse:
    try:
        meta = documents.print_document(doc_type, payload)
    except documents.UnknownDocumentType as exc:
        return _fail(doc_type, exc, 400)
    except pr.PrinterError as exc:
        return _fail(doc_type, exc, 503)
    except Exception as exc:  # 예상 못 한 실패도 500 으로 형태를 맞춰 돌려준다
        log.exception("출력 중 예상치 못한 오류 [%s]", doc_type)
        return _fail(doc_type, exc, 500)
    return JSONResponse(content={"ok": True, "docType": doc_type, **meta})


@app.get("/health", dependencies=PROTECTED)
def health():
    """백엔드/운영자가 프린터 연결 상태를 확인하는 용도."""
    printer_ready, detail = True, "ok"
    if config.DRY_RUN:
        detail = "DRY_RUN — 실제 출력 없음"
    else:
        try:
            with pr.open_printer():
                pass
        except pr.PrinterError as exc:
            printer_ready, detail = False, str(exc)
    return {
        "ok": True,
        "printerReady": printer_ready,
        "detail": detail,
        "config": {
            "kioskBaseUrl": config.KIOSK_BASE_URL,
            "qrSize": config.QR_SIZE,
            "qrNative": config.QR_NATIVE,
            "qrEcLevel": config.QR_EC_LEVEL,
            "sealSizePx": config.SEAL_SIZE_PX,
            "printerVid": f"{config.PRINTER_VID:#06x}",
            "printerPid": f"{config.PRINTER_PID:#06x}",
            "lineWidth": config.LINE_WIDTH,
            "dryRun": config.DRY_RUN,
            # 터널로 공개했는데 이 값이 false 면 즉시 토큰을 설정해야 한다.
            "authRequired": bool(config.AGENT_TOKEN),
        },
        "docTypes": list(documents.BUILDERS),
    }


@app.post("/print/ticket", response_model=schemas.PrintResult, dependencies=PROTECTED)
def print_ticket(payload: schemas.TicketPayload):
    """접수증(대기번호표). 접수 직후 자동 출력 + 화면에서 수동 재출력."""
    return _print("ticket", payload)


@app.post("/print/visit-summary", response_model=schemas.PrintResult, dependencies=PROTECTED)
def print_visit_summary(payload: schemas.VisitSummaryPayload):
    """진료 요약서. 진료 완료 시 자동 출력 + 화면에서 수동 재출력."""
    return _print("visit-summary", payload)


@app.post("/print/certificate-slip", response_model=schemas.PrintResult, dependencies=PROTECTED)
def print_certificate_slip(payload: schemas.CertificateSlipPayload):
    """증명서 발급 확인증.

    법정 서식(A4)을 대체하지 않는다. 감열지는 열·빛에 노출되면 수개월 안에
    글자가 사라져 보존용 서류로 쓸 수 없다 — documents.build_certificate_slip
    주석 참고.
    """
    return _print("certificate-slip", payload)


@app.post("/debug/qr-calibration", dependencies=PROTECTED)
def qr_calibration(body: schemas.QrCalibrationRequest | None = None):
    """QR 크기 실측용. 같은 URL 을 여러 크기로 연속 출력하고 끝에 한 번만 자른다.

    아이패드를 가진 팀원이 한 번 호출해서 어느 크기부터 읽히는지 비교하고,
    읽히는 것 중 가장 작은 값을 QR_SIZE 환경변수에 넣으면 된다.
    """
    body = body or schemas.QrCalibrationRequest()
    sizes = body.sizes or [6, 7, 8, 10]
    token = (body.token or "aB3xK9pQ").strip()
    try:
        meta = documents.print_qr_calibration(sizes, token)
    except pr.PrinterError as exc:
        return _fail("qr-calibration", exc, 503)
    except Exception as exc:
        log.exception("캘리브레이션 출력 실패")
        return _fail("qr-calibration", exc, 500)
    return {"ok": True, "docType": "qr-calibration", "sizes": sizes, **meta}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=config.AGENT_HOST, port=config.AGENT_PORT)
