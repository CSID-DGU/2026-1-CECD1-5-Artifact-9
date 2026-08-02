/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 태블릿이 접속할 키오스크 주소 기본값 (예: http://192.168.0.12:3000).
   * 비워두면 접수 화면의 현재 origin을 사용하며, 접수 화면에서 직접 덮어쓸 수 있다.
   */
  readonly VITE_KIOSK_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
