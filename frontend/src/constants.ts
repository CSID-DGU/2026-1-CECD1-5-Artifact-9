export const STORAGE_KEYS = {
  USER: "his_doctor",
  TOKEN: "his_token",
  /** 접수 화면에서 QR을 만들 때 쓰는 키오스크 접속 주소 (태블릿 기준 origin) */
  KIOSK_BASE_URL: "his_kiosk_base_url",
  /** 신규 환자 등록 때 휴대폰번호 칸에 미리 채울 번호 (이 브라우저에만 저장 — utils/defaultPatientPhone.ts) */
  DEFAULT_PATIENT_PHONE: "his_default_patient_phone",
} as const;
