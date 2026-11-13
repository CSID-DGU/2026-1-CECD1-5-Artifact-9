/**
 * 부스 운영 모드 (2026-11-16 ~ 11-20 부스 기간 전용).
 *
 * 이 폴더(src/booth/)는 부스의 재미용 기능만 모아 둔 곳이다. 지금은 하나 — 증명서 화면의 소견서를
 * "오늘의 운세 소견서"로 바꿔 찍는 기능이다. 방문객 이름·나이를 받아 이름을 크게 찍고, 소견 칸을
 * 띠별 운세와 포춘쿠키로 채운다.
 *
 * 켜고 끄는 스위치는 서버 쪽 부스 구성과 같다 — GitHub 저장소 변수 BOOTH_MODE.
 *   - BOOTH_MODE=true 로 배포하면 docker-compose.booth.yml 이 frontend 이미지를 VITE_BOOTH_MODE=true 로 빌드한다.
 *   - 부스가 끝나 false 로 다시 배포하면 이 값 없이 다시 빌드되어 평소 소견서로 돌아간다. 코드는 그대로 둬도 된다.
 * 빌드할 때 굳는 값이라 화면에서 켜고 끌 수 없다. 로컬에서 보려면 frontend/.env.local 에 VITE_BOOTH_MODE=true.
 *
 * 코드까지 걷어 내려면 이 폴더를 지우고, `../booth/` 를 import 하는 두 파일(pages/Certificate.tsx,
 * components/CertificateDocumentView.tsx)에서 "부스" 주석이 붙은 줄을 지운다. 순서는 docs/booth-operation-guide.md 8단계.
 */
export const BOOTH_MODE = import.meta.env.VITE_BOOTH_MODE === "true";

/** 운세를 넣을 때 용도 칸이 비어 있으면 채우는 값. 발급 이력에서 부스 소견서를 알아보는 표시도 된다. */
export const BOOTH_PURPOSE = "부스 체험 기념";

/** 운세 소견서의 맺음말. 평소 소견서는 "위와 같이 소견을 제출합니다." */
export const BOOTH_CLOSING = "위와 같이 오늘의 운세를 소견합니다.";

/** 인쇄물 맨 아래에 찍는 안내. 진짜 의료 문서처럼 보이는 종이가 밖으로 나가므로 반드시 남긴다. */
export const BOOTH_DISCLAIMER =
  "※ 부스 체험용 출력물입니다. 실제 진료 기록이나 의학적 소견이 아니며 법적 효력이 없습니다.";
