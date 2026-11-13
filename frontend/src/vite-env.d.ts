/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 태블릿이 접속할 키오스크 주소 기본값 (예: http://192.168.0.12:3000).
   * 비워두면 접수 화면의 현재 origin을 사용하며, 접수 화면에서 직접 덮어쓸 수 있다.
   */
  readonly VITE_KIOSK_BASE_URL?: string;
  /**
   * "true" 면 부스 기간 기능(소견서 → 오늘의 운세 소견서)을 켠다. src/booth/boothMode.ts 참고.
   * 운영에서는 docker-compose.booth.yml 이 빌드 인자로 넘긴다 — 저장소 변수 BOOTH_MODE 와 함께 켜지고 꺼진다.
   */
  readonly VITE_BOOTH_MODE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
