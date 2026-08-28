import { apiRequest } from "./client";

/** 백엔드 CertificateType 과 1:1. 값을 바꾸면 서버 enum 도 함께 바꿔야 한다. */
export type CertificateType =
  | "PRESCRIPTION"
  | "DIAGNOSIS"
  | "TREATMENT_CONFIRMATION"
  | "MEDICAL_OPINION"
  | "REFERRAL";

export type CertificateStatus = "ISSUED" | "VOID";

export type DiseaseLine = {
  code: string;
  name: string;
  primary: boolean;
};

export type DrugLine = {
  name: string;
  dosage: string | null;
  durationDays: number | null;
  notes: string | null;
};

/**
 * 발급 시점에 종이에 찍힌 값 전부. 서버가 굳혀 보관하는 스냅샷이라
 * 화면은 이 객체만으로 서류를 그대로 다시 그린다.
 *
 * `patientResidentNo`는 마스킹된 표시값이다(예: `900101-1******`).
 * 주민등록번호 원본은 이 시스템 어디에도 저장되지 않으며, 생년월일과 성별로 계산해 문서에만 찍는다.
 */
export type CertificateDocument = {
  hospitalName: string | null;
  hospitalAddress: string | null;
  hospitalPhone: string | null;
  hospitalRegistrationNo: string | null;

  patientName: string | null;
  patientResidentNo: string | null;
  patientGender: string | null;
  patientBirthDate: string | null;
  patientPhone: string | null;

  visitDate: string | null;
  treatmentPeriodFrom: string | null;
  treatmentPeriodTo: string | null;
  diseases: DiseaseLine[] | null;

  opinion: string | null;
  treatmentPlan: string | null;
  referralReason: string | null;
  remarks: string | null;

  referralTo: string | null;

  drugs: DrugLine[] | null;
  prescriptionValidDays: number | null;

  purpose: string | null;
  submitTo: string | null;
  doctorName: string | null;
  doctorLicenseNo: string | null;
  department: string | null;
  issuedDate: string | null;
  serialNo: string | null;

  formCode: string | null;
  legalBasis: string | null;
};

export type CertificateResponse = {
  id: number;
  serialNo: string | null;
  visitId: number;
  patientId: number;

  type: CertificateType;
  typeLabel: string;
  statutory: boolean;
  legalBasis: string | null;
  formCode: string | null;

  purpose: string | null;
  submitTo: string | null;

  issuerName: string;
  issuerLicense: string | null;
  issuedAt: string;

  status: CertificateStatus;
  voidReason: string | null;
  voidedAt: string | null;

  reissueOf: number | null;

  aiModel: string | null;
  aiEdited: boolean | null;

  content: CertificateDocument;
};

/** 발급대장 목록 한 줄. 본문 스냅샷은 빠져 있다 — 클릭해서 단건 조회로 받아온다. */
export type CertificateSummary = {
  id: number;
  serialNo: string | null;
  type: CertificateType;
  typeLabel: string;
  visitId: number;
  patientName: string | null;
  purpose: string | null;
  submitTo: string | null;
  issuerName: string;
  issuedAt: string;
  status: CertificateStatus;
  reissueOf: number | null;
};

export type CertificateDraftRequest = {
  type: CertificateType;
  purpose?: string | null;
  submitTo?: string | null;
  referralTo?: string | null;
};

/**
 * `generated=false`는 오류가 아니다. AI 초안은 타자를 대신 쳐주는 편의 기능이라
 * 실패하면 의사가 직접 써서 그대로 발급하면 된다.
 */
export type CertificateDraftResponse = {
  opinion: string | null;
  treatmentPlan: string | null;
  referralReason: string | null;
  model: string | null;
  generated: boolean;
  message: string | null;
};

/**
 * 병명·약품명·날짜·면허번호는 보내지 않는다 — 서버가 진료기록에서 직접 채운다.
 * 화면이 정할 수 있는 것은 용도·제출처와 의사가 쓴 서술 내용뿐이다.
 */
export type CertificateIssueRequest = {
  type: CertificateType;
  purpose?: string | null;
  submitTo?: string | null;
  opinion?: string | null;
  treatmentPlan?: string | null;
  referralReason?: string | null;
  remarks?: string | null;
  referralTo?: string | null;
  prescriptionValidDays?: number | null;
  aiDraft?: string | null;
  aiModel?: string | null;
  aiEdited?: boolean | null;
};

export function draftCertificate(visitId: number, req: CertificateDraftRequest) {
  return apiRequest<CertificateDraftResponse>(`/api/v1/visits/${visitId}/certificates/draft`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function issueCertificate(visitId: number, req: CertificateIssueRequest) {
  return apiRequest<CertificateResponse>(`/api/v1/visits/${visitId}/certificates`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function listCertificatesByVisit(visitId: number) {
  return apiRequest<CertificateSummary[]>(`/api/v1/visits/${visitId}/certificates`);
}

export function listCertificatesByPatient(patientId: number) {
  return apiRequest<CertificateSummary[]>(`/api/v1/certificates?patientId=${patientId}`);
}

export function getCertificate(certificateId: number) {
  return apiRequest<CertificateResponse>(`/api/v1/certificates/${certificateId}`);
}

export function reissueCertificate(certificateId: number) {
  return apiRequest<CertificateResponse>(`/api/v1/certificates/${certificateId}/reissue`, {
    method: "POST",
  });
}

export function voidCertificate(certificateId: number, reason: string) {
  return apiRequest<CertificateResponse>(`/api/v1/certificates/${certificateId}/void`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

/** 화면에 나열할 순서 — 발급 빈도가 높은 것부터. */
export const CERTIFICATE_TYPES: Array<{
  type: CertificateType;
  label: string;
  description: string;
  statutory: boolean;
  doctorOnly: boolean;
}> = [
  {
    type: "PRESCRIPTION",
    label: "처방전",
    description: "확정된 처방 내역 (법정서식)",
    statutory: true,
    doctorOnly: false,
  },
  {
    type: "DIAGNOSIS",
    label: "진단서",
    description: "병명·향후 치료 소견 (법정서식)",
    statutory: true,
    doctorOnly: true,
  },
  {
    type: "TREATMENT_CONFIRMATION",
    label: "진료확인서",
    description: "진료 사실만 확인 (병명 미기재)",
    statutory: false,
    doctorOnly: false,
  },
  {
    type: "MEDICAL_OPINION",
    label: "소견서",
    description: "진료 경과·의학적 소견",
    statutory: false,
    doctorOnly: true,
  },
  {
    type: "REFERRAL",
    label: "진료의뢰서",
    description: "상급 의료기관 의뢰 (법정서식)",
    statutory: true,
    doctorOnly: true,
  },
];
