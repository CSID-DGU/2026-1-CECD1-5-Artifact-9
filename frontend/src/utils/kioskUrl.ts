import { STORAGE_KEYS } from "../constants";

/**
 * 태블릿이 QR을 찍고 열게 될 주소를 만든다.
 *
 * 접수 화면(맥북)의 origin과 태블릿이 접속해야 하는 origin이 다를 수 있어서 별도 설정이 필요하다.
 *   - Wi-Fi LAN        → http://192.168.0.12:3000  (맥북의 LAN IP)
 *   - adb reverse(USB) → http://localhost:3000     (태블릿 localhost가 맥북으로 포워딩됨)
 *   - 맥북에서 직접 테스트 → 미설정 시 현재 origin
 *
 * 우선순위: localStorage(현장에서 즉시 변경) → VITE_KIOSK_BASE_URL → window.location.origin
 */
export function getKioskBaseUrl(): string {
  const stored = localStorage.getItem(STORAGE_KEYS.KIOSK_BASE_URL)?.trim();
  if (stored) return normalize(stored);

  const fromEnv = import.meta.env.VITE_KIOSK_BASE_URL?.trim();
  if (fromEnv) return normalize(fromEnv);

  return window.location.origin;
}

export function setKioskBaseUrl(baseUrl: string) {
  const trimmed = baseUrl.trim();
  if (trimmed) localStorage.setItem(STORAGE_KEYS.KIOSK_BASE_URL, normalize(trimmed));
  else localStorage.removeItem(STORAGE_KEYS.KIOSK_BASE_URL);
}

/**
 * 키오스크 진입 URL. 안드로이드 크롬이 검색어로 오인하지 않도록 스킴을 반드시 포함시킨다.
 * baseUrl을 넘기면 그 값을 쓴다 — 접수 화면처럼 주소를 화면에서 바꾸는 경우 상태값을 그대로 넘긴다.
 */
export function buildKioskUrl(token: string, baseUrl?: string): string {
  const base = baseUrl?.trim() ? normalize(baseUrl) : getKioskBaseUrl();
  return `${base}/kiosk/${token}`;
}

/** 스킴 보정 + 끝 슬래시 제거 — "192.168.0.12:3000/" 처럼 입력해도 동작하게 한다. */
function normalize(baseUrl: string): string {
  const withScheme = /^https?:\/\//i.test(baseUrl) ? baseUrl : `http://${baseUrl}`;
  return withScheme.replace(/\/+$/, "");
}
