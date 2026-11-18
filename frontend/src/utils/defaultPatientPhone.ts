import { STORAGE_KEYS } from "../constants";

/**
 * 신규 환자 등록 때 휴대폰번호 칸에 미리 채울 번호. 이 기기(브라우저)에만 저장한다.
 *
 * 부스에서는 방문객 이름만 받고, 번호는 운영자 번호 하나로 등록한다(접수 알림을 그 번호로 받는다).
 * 번호를 코드에 넣지 않는 이유: 공개 저장소라 커밋하면 누구나 보게 되고, 배포된 화면 JS 에도 그대로 실린다.
 * 접수 노트북에서 한 번 [이 번호를 기본값으로]를 누르면 그 브라우저에서는 계속 채워진다.
 *
 * 저장소를 쓸 수 없는 브라우저(사생활 보호 모드 등)에서는 기본값 없이 전처럼 빈 칸으로 동작한다.
 */
export function getDefaultPatientPhone(): string {
  try {
    return localStorage.getItem(STORAGE_KEYS.DEFAULT_PATIENT_PHONE)?.trim() ?? "";
  } catch {
    return "";
  }
}

/** 빈 값이면 기본값을 지운다. */
export function saveDefaultPatientPhone(phone: string): void {
  const trimmed = phone.trim();
  try {
    if (trimmed) localStorage.setItem(STORAGE_KEYS.DEFAULT_PATIENT_PHONE, trimmed);
    else localStorage.removeItem(STORAGE_KEYS.DEFAULT_PATIENT_PHONE);
  } catch {
    /* 저장소를 못 쓰는 브라우저 — 이번 화면에서만 기본값으로 쓴다 */
  }
}
