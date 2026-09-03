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
 *
 * 증명서는 토큰만으로 열리지 않는다. 링크가 살아 있는지 확인하는 단계와
 * 환자 생년월일을 맞춰 내용을 받는 단계가 나뉘어 있다 — 종이를 주운 사람이
 * QR 만 찍어서 진단명과 소견까지 읽는 일을 막기 위해서다.
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

/**
 * 본인 확인 화면을 열기 전 점검 결과.
 *
 * 문서 내용이 없다는 것이 핵심이다. 링크가 살아 있는지와 언제 닫히는지만 알려준다 —
 * 내용을 미리 받아두고 화면에서만 가리면 개발자도구 Network 탭에 그대로 남는다.
 */
export type SharedDocumentGate = {
  expiresAt: string;
};

/**
 * 발급확인증 QR → 링크가 열람 가능한 상태인지 확인.
 * 없는 토큰이면 404, 기간이 지났으면 410. 증명서 내용은 여기서 나오지 않는다.
 */
export function getSharedCertificateGate(token: string) {
  return apiRequest<SharedDocumentGate>(
    `/api/public/documents/certificate/${encodeURIComponent(token)}`
  );
}

/**
 * 환자 생년월일을 맞춰 증명서를 연다.
 *
 * `birthDate` 는 숫자 8자리(`19900101`)로 보낸다. 서버가 숫자만 남겨 비교하므로
 * 하이픈이 섞여도 통과하지만, 보내는 쪽에서 형식을 하나로 고정해 두는 편이 낫다.
 *
 * 생년월일이 틀리면 403, 반복해서 틀려 잠기면 429가 온다 — 두 경우의 안내가 달라야 해서
 * 화면에서 상태 코드를 구분해 처리한다(SharedCertificate.tsx).
 */
export function verifySharedCertificate(token: string, birthDate: string) {
  return apiRequest<SharedCertificate>(
    `/api/public/documents/certificate/${encodeURIComponent(token)}/verify`,
    { method: "POST", body: JSON.stringify({ birthDate }) }
  );
}

/** 진료요약서 QR → 진료 1건. 없는 토큰이면 404, 기간이 지났으면 410. */
export function getSharedVisitSummary(token: string) {
  return apiRequest<SharedVisitSummary>(
    `/api/public/documents/visit-summary/${encodeURIComponent(token)}`
  );
}
