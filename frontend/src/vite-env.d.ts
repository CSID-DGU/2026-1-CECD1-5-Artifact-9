/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 태블릿이 접속할 키오스크 주소 기본값 (예: http://192.168.0.12:3000).
   * 비워두면 접수 화면의 현재 origin을 사용하며, 접수 화면에서 직접 덮어쓸 수 있다.
   */
  readonly VITE_KIOSK_BASE_URL?: string;
  /**
   * API 서버의 출처(끝에 / 없이). 예: https://api.example.com
   * 비워 두면 화면과 같은 출처의 /api 를 부른다 — 로컬 Vite(proxy)와 도커 nginx 가 이 방식이다.
   * 화면은 S3 + CloudFront, API 는 ALB 뒤에 따로 둘 때만 넣는다. 빌드할 때 굳으므로 바꾸면 다시 배포한다.
   * 쓰는 곳은 src/api/base.ts 하나다.
   */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
