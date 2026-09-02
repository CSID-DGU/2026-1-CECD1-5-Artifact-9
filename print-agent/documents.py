"""문서 종류별 레이아웃.

새 출력물이 필요하면 여기에 빌더 함수 하나를 더하고 BUILDERS 에 등록하면
된다. 프린터 연결·초기화·커팅은 print_document() 가 공통으로 처리한다.
"""

import logging
from datetime import datetime

import config
import printer as pr
import schemas

log = logging.getLogger("print-agent.documents")


class UnknownDocumentType(ValueError):
    pass


def _fmt_dt(raw: str | None) -> str:
    """ISO 문자열을 '2026-09-01 14:23' 로. 파싱 안 되면 원문 그대로."""
    if not raw:
        return "-"
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00")).strftime("%Y-%m-%d %H:%M")
    except ValueError:
        return raw


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M")


def _kiosk_base(requested: str | None) -> str:
    """접수증 QR 에 쓸 base URL 을 고른다.

    접수 화면이 보내온 값을 우선한다. 접수 담당자가 화면에서 키오스크 주소를
    바꿀 수 있는데(Reception.tsx 의 '키오스크 접속 주소'), 그때 화면 QR 만 바뀌고
    종이 QR 이 기본값에 머물면 환자가 엉뚱한 주소로 이동한다.

    백엔드(KioskBaseUrlPolicy)에서 이미 형식·허용목록 검사를 마친 값이지만,
    스킴만은 여기서도 확인한다. 이 에이전트가 터널로 공개돼 있으면 백엔드를
    거치지 않은 요청이 직접 들어올 수 있고, QR 은 사람이 내용을 읽을 수 없는
    출력물이라 이상한 값이 섞여도 종이만 봐서는 알아챌 수 없기 때문이다.
    """
    if requested:
        cleaned = requested.strip().rstrip("/")
        if cleaned.startswith(("http://", "https://")):
            return cleaned
        log.warning("스킴이 없는 kioskBaseUrl 을 무시하고 기본값을 쓴다: %r", requested)
    return config.KIOSK_BASE_URL


# ── 접수증 ─────────────────────────────────────────────────────────────────
def build_ticket(p, data: schemas.TicketPayload) -> dict:
    pr.style(p, align_mode="center")
    pr.kr_line(p, "대기번호")

    pr.style(p, align_mode="center", is_bold=True, w=2, h=2)
    pr.kr_line(p, data.visitNo)
    pr.reset_style(p)

    pr.feed(p, 1)
    pr.label_value(p, "환자명", data.patientName)
    pr.label_value(p, "환자번호", data.patientNo)

    pr.divider(p)

    # QR 에는 토큰이 아니라 완전한 URL 을 넣는다. 아이패드 기본 카메라가
    # URL 일 때만 사파리로 이동시켜 주기 때문이다.
    meta = pr.render_qr(p, f"{_kiosk_base(data.kioskBaseUrl)}/kiosk/{data.kioskToken}")

    pr.align(p, "center")
    pr.kr_line(p, "카메라로 QR을 촬영해 주세요")
    pr.reset_style(p)

    pr.feed(p, 1)
    return meta


# ── 진료 요약서 ─────────────────────────────────────────────────────────────
def build_visit_summary(p, data: schemas.VisitSummaryPayload) -> dict:
    pr.align(p, "center")
    pr.kr_line(p, config.HOSPITAL_NAME)
    pr.bold(p, True)
    pr.kr_line(p, "진 료 요 약 서")
    pr.reset_style(p)

    pr.divider(p)
    pr.label_value(p, "환자명", f"{data.patientName} ({data.patientNo})")
    pr.label_value(p, "진료일시", _fmt_dt(data.visitDateTime))
    pr.label_value(p, "담당의", data.doctorName)

    pr.feed(p, 1)
    pr.kr_line(p, "[진단]")
    if data.diseases:
        for d in data.diseases:
            line = f"- {d.code} {d.nameKo}"
            # 처방 상병에는 중증도 컬럼이 없어 보통 null 로 온다. 값이 있을 때만 붙인다.
            if d.severityLevel:
                line += f" ({d.severityLevel})"
            pr.kr_wrapped(p, line, indent="   ")
    else:
        pr.kr_line(p, "- 기록 없음")

    pr.feed(p, 1)
    pr.kr_line(p, "[처방]")
    if data.prescriptions:
        for m in data.prescriptions:
            # 긴 약품명은 접히되, 이어지는 줄이 새 항목처럼 보이지 않도록 들여쓴다.
            pr.kr_wrapped(p, f"- {m.drugName}", indent="   ")
            usage = " / ".join(
                x for x in (m.dosage, f"{m.durationDays}일" if m.durationDays else None) if x
            )
            if usage:
                pr.kr_wrapped(p, f"   {usage}", indent="     ")
    else:
        pr.kr_line(p, "- 처방 없음")

    if data.aiSummary and data.aiSummary.strip():
        pr.feed(p, 1)
        pr.kr_line(p, "[AI 분석 참고]")
        pr.kr_wrapped(p, data.aiSummary.strip(), indent=" ")
        # AI 문장이 그대로 환자 손에 나가면 안 되므로, 의사가 확인한 것임을
        # 반드시 같이 찍는다.
        pr.kr_line(p, "※ 의사 확인 완료")

    pr.divider(p)
    meta = pr.render_qr(
        p, config.VISIT_SUMMARY_URL_TEMPLATE.format(base=config.PORTAL_BASE_URL, visitId=data.visitId)
    )

    pr.align(p, "center")
    pr.kr_line(p, f"발급일시 {_now()}")
    pr.reset_style(p)

    pr.feed(p, 1)
    return meta


# ── 증명서 발급 확인증 ───────────────────────────────────────────────────────
def build_certificate_slip(p, data: schemas.CertificateSlipPayload) -> dict:
    """감열지 발급 확인증.

    ※ 이 출력물은 법정 서식이 아니다.
    법적 효력이 있는 증명서는 기존 A4 인쇄 흐름(Certificate.tsx 의
    window.print())으로만 발급한다. 여기서 찍는 종이는 "그 증명서가 실제로
    발급되었다"는 사실을 환자에게 안내하는 보조 출력물일 뿐이다.

    이유: 감열지는 감열층의 발색 반응으로 글자를 만드는 종이라, 열·직사광선·
    가소제(비닐 파일, 영수증 지갑)에 닿으면 수개월 안에 글자가 사라진다.
    보존이 필요한 서류에는 쓸 수 없다. 그래서 A4 흐름은 손대지 않는다.
    """
    pr.align(p, "center")
    pr.kr_line(p, config.HOSPITAL_NAME)
    pr.bold(p, True)
    pr.kr_line(p, "증명서 발급 확인증")
    pr.reset_style(p)

    pr.divider(p)
    pr.label_value(p, "서류종류", data.typeLabel)
    pr.label_value(p, "환자명", f"{data.patientName} ({data.patientNo})")
    pr.label_value(p, "발급번호", data.serialNo)
    pr.label_value(p, "발급일시", _fmt_dt(data.issuedAt))
    issuer = data.issuerName
    if data.issuerLicenseNo:
        issuer += f" (면허 {data.issuerLicenseNo})"
    pr.label_value(p, "발급자", issuer)

    pr.feed(p, 1)
    pr.render_seal(p)
    pr.feed(p, 1)

    # QR 에는 발급번호가 들어간 진위확인 URL 만 넣는다. 환자 이름·생년월일 같은
    # 개인정보는 절대 인코딩하지 않는다 — 종이를 주운 사람이 읽을 수 있게 된다.
    meta = pr.render_qr(
        p,
        config.CERTIFICATE_VERIFY_URL_TEMPLATE.format(
            base=config.PORTAL_BASE_URL, serialNo=data.serialNo
        ),
    )

    pr.align(p, "center")
    pr.kr_line(p, "위 서류가 정히 발급되었음을 확인합니다.")
    pr.kr_line(p, "※ 본 확인증은 안내용이며,")
    pr.kr_line(p, "법정 서식(A4)을 대체하지 않습니다.")
    pr.reset_style(p)

    pr.feed(p, 1)
    return meta


# ── QR 캘리브레이션 ─────────────────────────────────────────────────────────
def build_qr_calibration(p, sizes: list[int], token: str) -> dict:
    """같은 URL 을 여러 크기로 연속 출력한다. 커팅은 맨 끝에 한 번만.

    아이패드 카메라로 어느 크기부터 읽히는지 눈으로 비교하기 위한 것이다.
    실측이 끝나면 읽히는 것 중 가장 작은 값을 QR_SIZE 에 넣는다.
    """
    url = f"{config.KIOSK_BASE_URL}/kiosk/{token}"

    pr.align(p, "center")
    pr.bold(p, True)
    pr.kr_line(p, "QR 크기 캘리브레이션")
    pr.reset_style(p)
    pr.kr_wrapped(p, url, indent=" ")
    pr.kr_line(p, f"길이 {len(url)}자 / EC={config.QR_EC_LEVEL} / native={config.QR_NATIVE}")
    pr.divider(p)

    rendered = []
    for s in sizes:
        pr.align(p, "center")
        pr.bold(p, True)
        p._raw(f"size={s}".encode("ascii"))  # 영숫자만이라 text 경로로 충분
        p._raw(b"\n")
        pr.reset_style(p)
        try:
            meta = pr.render_qr(p, url, qr_size=s)
            rendered.append({"size": s, "native": meta["qrNative"]})
        except pr.PrinterError as exc:
            log.warning("size=%s QR 출력 실패: %s", s, exc)
            pr.align(p, "center")
            pr.kr_line(p, "(출력 실패)")
            pr.reset_style(p)
            rendered.append({"size": s, "error": str(exc)})
        pr.feed(p, 1)
        pr.divider(p)

    pr.align(p, "center")
    pr.kr_line(p, "읽히는 것 중 가장 작은 값을")
    pr.kr_line(p, "QR_SIZE 에 넣으세요")
    pr.reset_style(p)
    pr.feed(p, 1)
    return {"url": url, "rendered": rendered}


# ── 디스패처 ────────────────────────────────────────────────────────────────
BUILDERS = {
    "ticket": (schemas.TicketPayload, build_ticket),
    "visit-summary": (schemas.VisitSummaryPayload, build_visit_summary),
    "certificate-slip": (schemas.CertificateSlipPayload, build_certificate_slip),
}


def print_document(doc_type: str, payload) -> dict:
    """문서 하나를 출력한다. 연결·초기화·커팅은 여기서 공통 처리한다."""
    entry = BUILDERS.get(doc_type)
    if entry is None:
        raise UnknownDocumentType(
            f"알 수 없는 문서 종류입니다: {doc_type} (가능: {', '.join(BUILDERS)})"
        )
    model_cls, builder = entry
    data = payload if isinstance(payload, model_cls) else model_cls(**payload)

    with pr.open_printer() as p:
        # 초기화를 빼먹으면 직전 문서 내용이 뒤에 다시 찍힌다.
        pr.reset(p)
        meta = builder(p, data) or {}
        pr.feed(p, 2)
        pr.cut(p)
    log.info("출력 완료: %s %s", doc_type, meta)
    return meta


def print_qr_calibration(sizes: list[int], token: str) -> dict:
    with pr.open_printer() as p:
        pr.reset(p)
        meta = build_qr_calibration(p, sizes, token)
        pr.feed(p, 2)
        pr.cut(p)
    return meta
