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
