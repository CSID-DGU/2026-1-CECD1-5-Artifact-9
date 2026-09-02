"""요청 본문 스키마.

백엔드가 보내는 필드 이름은 기존 API 응답(PrescriptionResponse,
CertificateResponse)의 이름을 그대로 따른다. 감열지 출력을 위해 백엔드에서
새로 계산해야 하는 값이 없도록 맞춰둔 것이다.
"""

from typing import Literal, Optional

from pydantic import BaseModel, Field

DocType = Literal["ticket", "visit-summary", "certificate-slip"]


class TicketPayload(BaseModel):
    """접수증(대기번호표)."""

    visitNo: str
    patientName: str
    patientNo: str
    kioskToken: str

    # 접수 화면이 실제로 보고 있는 키오스크 주소. 화면에 뜬 QR 과 종이에 찍힌 QR 이
    # 같은 곳을 가리키게 하려고 백엔드가 그대로 넘겨준다(백엔드 KioskBaseUrlPolicy
    # 에서 형식·허용목록 검사를 통과한 값만 온다).
    #
    # 없으면 config.KIOSK_BASE_URL 을 쓴다 — 이 필드가 없던 시절의 백엔드나
    # curl 스모크 테스트도 그대로 동작해야 하기 때문이다.
    kioskBaseUrl: Optional[str] = None


class DiseaseItem(BaseModel):
    code: str
    nameKo: str
    # 처방 상병에는 중증도 컬럼이 없다(중증도는 AI 질병 테이블에만 있음).
    # 백엔드는 이 값을 null 로 보내고, 값이 없으면 줄에서 생략한다.
    severityLevel: Optional[str] = None


class PrescriptionItem(BaseModel):
    drugName: str
    dosage: Optional[str] = None
    durationDays: Optional[int] = None


class VisitSummaryPayload(BaseModel):
    """진료 요약서."""

    visitId: int
    patientName: str
    patientNo: str
    visitDateTime: str
    doctorName: str
    diseases: list[DiseaseItem] = Field(default_factory=list)
    prescriptions: list[PrescriptionItem] = Field(default_factory=list)
    aiSummary: Optional[str] = None
    # 종이 QR 이 가리킬 열람 링크의 토큰. 백엔드가 발급한다.
    # QR 에는 환자 이름·생년월일 같은 개인정보를 절대 싣지 않는다 —
    # 실리는 것은 이 토큰이 들어간 URL 뿐이고, 내용은 링크를 열었을 때 서버가 준다.
    # 구버전 백엔드나 curl 테스트에서는 비어 올 수 있고, 그때는 QR 을 생략한다.
    shareToken: Optional[str] = None


class CertificateSlipPayload(BaseModel):
    """증명서 발급 확인증.

    법정 서식(A4)을 대체하지 않는다. 아래 print_certificate_slip 주석 참고.
    """

    certificateId: int
    typeLabel: str
    patientName: str
    patientNo: str
    serialNo: str
    issuedAt: str
    issuerName: str
    issuerLicenseNo: Optional[str] = None
    # 위 VisitSummaryPayload.shareToken 과 같다. 발급번호(serialNo)는 순번이라
    # URL 에 쓰지 않는다 — 앞뒤 번호로 남의 증명서를 열어볼 수 있기 때문이다.
    shareToken: Optional[str] = None


class QrCalibrationRequest(BaseModel):
    """QR 크기 실측용. 같은 URL 을 여러 크기로 연속 출력한다."""

    sizes: Optional[list[int]] = None
    token: Optional[str] = None


class PrintResult(BaseModel):
    ok: bool
    docType: str
    detail: Optional[str] = None
    qrSize: Optional[int] = None
    qrNative: Optional[bool] = None
