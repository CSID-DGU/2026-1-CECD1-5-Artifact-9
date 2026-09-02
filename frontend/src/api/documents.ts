import { apiRequest } from "./client";
import type {
  CertificateDocument,
  CertificateStatus,
  CertificateType,
} from "./certificates";

/**
 * 감열지 QR 로 들어오는 환자용 열람 API.
 *
 * 다른 api 모듈과 달리 이 경로들은 로그인 없이 열려 있다. 대신 주소에 실리는
 * base62 12자 토큰이 문서 한 건만 열고, 유효기간이 지나면 410 이 온다.
 * 그래서 여기 있는 응답 타입에는 다른 문서로 이동할 수 있는 ID 가 들어 있지 않다 —
 * 서버가 내려주지 않기 때문이고, 앞으로도 추가하지 않는다.
 */

/**
 * 증명서 열람 응답.
 *
 * `CertificateResponse` 와 달리 id·visitId·patientId 가 없다. 공개 응답에
 * 내부 식별자를 실어 보내지 않기 위해서다. 화면을 그리는 데 필요한 것은
 * 발급 당시 스냅샷(`content`)뿐이라 실제로 아쉬울 것도 없다.
 */
export type SharedCertificate = {
  type: CertificateType;
  typeLabel: string;
  status: CertificateStatus;
  voidReason: string | null;
  /** 재발급본이면 제목 옆에 [재발급] 을 찍는다. 원본 증명서 ID 는 내려오지 않는다. */
  reissued: boolean;
  content: CertificateDocument;
  /** 이 링크가 열리지 않게 되는 시각. 화면 하단 안내에 쓴다. */
  expiresAt: string;
};

export type SharedVisitSummary = {
  visitNo: string;
  patientName: string;
  patientNo: string;
  visitDateTime: string | null;
  /** 처방이 지워진 접수는 진료 정보만 내려온다. 그때 이 아래 값들이 비어 있다. */
  doctorName: string | null;
  diseases: { code: string; nameKo: string }[];
  prescriptions: { drugName: string; dosage: string | null; durationDays: number | null }[];
  aiSummary: string | null;
  expiresAt: string;
};

/** 발급확인증 QR → 증명서 1건. 없는 토큰이면 404, 기간이 지났으면 410. */
export function getSharedCertificate(token: string) {
  return apiRequest<SharedCertificate>(
    `/api/public/documents/certificate/${encodeURIComponent(token)}`
  );
}

/** 진료요약서 QR → 진료 1건. 없는 토큰이면 404, 기간이 지났으면 410. */
export function getSharedVisitSummary(token: string) {
  return apiRequest<SharedVisitSummary>(
    `/api/public/documents/visit-summary/${encodeURIComponent(token)}`
  );
}
