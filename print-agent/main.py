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

from fastapi import FastAPI
from fastapi.responses import JSONResponse

import config
import documents
import printer as pr
import schemas

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
)
log = logging.getLogger("print-agent")

app = FastAPI(
    title="Artifact print-agent",
    description="감열지 POS 프린터(SEWOO SLK-TS100) 출력 에이전트",
    version="1.0.0",
)


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


@app.get("/health")
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
        },
        "docTypes": list(documents.BUILDERS),
    }


@app.post("/print/ticket", response_model=schemas.PrintResult)
def print_ticket(payload: schemas.TicketPayload):
    """접수증(대기번호표). 접수 직후 자동 출력 + 화면에서 수동 재출력."""
    return _print("ticket", payload)


@app.post("/print/visit-summary", response_model=schemas.PrintResult)
def print_visit_summary(payload: schemas.VisitSummaryPayload):
    """진료 요약서. 진료 완료 시 자동 출력 + 화면에서 수동 재출력."""
    return _print("visit-summary", payload)


@app.post("/print/certificate-slip", response_model=schemas.PrintResult)
def print_certificate_slip(payload: schemas.CertificateSlipPayload):
    """증명서 발급 확인증.

    법정 서식(A4)을 대체하지 않는다. 감열지는 열·빛에 노출되면 수개월 안에
    글자가 사라져 보존용 서류로 쓸 수 없다 — documents.build_certificate_slip
    주석 참고.
    """
    return _print("certificate-slip", payload)


@app.post("/debug/qr-calibration")
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
