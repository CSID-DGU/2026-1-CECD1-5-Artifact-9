import { getKioskBaseUrl } from "../utils/kioskUrl";

/**
 * 접수증 QR 이 화면 QR 과 같은 주소를 가리키게 하는 헤더.
 *
 * 화면 QR은 브라우저가 `getKioskBaseUrl()`로 그 자리에서 만들지만, 종이 QR은
 * 접수 데스크 맥북의 print-agent가 자기 `.env`(KIOSK_BASE_URL)를 보고 만든다.
 * 둘은 서로를 모른다 — 접수 담당자가 화면에서 키오스크 주소를 바꾸면 화면 QR만
 * 바뀌고 종이 QR은 그대로여서, 환자가 엉뚱한 주소로 이동하게 된다.
 *
 * 그래서 접수·티켓 재출력 요청에 이 헤더를 실어 지금 화면이 쓰는 주소를 알린다.
 * 백엔드(KioskBaseUrlPolicy)가 형식과 허용 목록을 검사한 뒤 print-agent로 넘긴다.
 * 헤더가 없거나 검사에 걸리면 print-agent의 기본 주소가 쓰인다 — 출력이 실패하지는 않는다.
 */
export const KIOSK_BASE_URL_HEADER = "X-Kiosk-Base-Url";

/**
 * @param baseUrl 접수 화면처럼 주소를 화면에서 바꾸는 경우 그 상태값을 넘긴다.
 *                생략하면 현재 저장된 값(localStorage → 현재 origin)을 쓴다.
 */
export function kioskBaseHeader(baseUrl?: string): Record<string, string> {
  const value = baseUrl?.trim() ? baseUrl.trim() : getKioskBaseUrl();
  return value ? { [KIOSK_BASE_URL_HEADER]: value } : {};
}
