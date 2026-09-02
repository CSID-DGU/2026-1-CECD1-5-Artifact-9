import { STORAGE_KEYS } from "../constants";

/**
 * 태블릿이 QR을 찍고 열게 될 주소를 만든다.
 *
 * 기본값은 현재 접속한 사이트의 origin이다.
 *   - 배포 사이트 접속 → https://... 배포 도메인
 *   - Mac IP 접속     → http://192.168.0.12:3000
 *   - localhost 접속  → http://localhost:3000
 *
 * 단, 현장 시연처럼 접수 PC와 태블릿 접속 주소를 강제로 맞춰야 할 때만 localStorage 수동값을 쓴다.
 */
export function getKioskBaseUrl(): string {
  return getManualKioskBaseUrl() ?? getCurrentKioskBaseUrl();
}

export function getCurrentKioskBaseUrl(): string {
  return normalize(window.location.origin);
}

export function getManualKioskBaseUrl(): string | null {
  const stored = localStorage.getItem(STORAGE_KEYS.KIOSK_BASE_URL)?.trim();
  if (stored) return normalize(stored);
  return null;
}

export function setKioskBaseUrl(baseUrl: string) {
  const trimmed = baseUrl.trim();
  if (trimmed) localStorage.setItem(STORAGE_KEYS.KIOSK_BASE_URL, normalize(trimmed));
  else localStorage.removeItem(STORAGE_KEYS.KIOSK_BASE_URL);
}

export function resetKioskBaseUrlToCurrent(): string {
  localStorage.removeItem(STORAGE_KEYS.KIOSK_BASE_URL);
  return getCurrentKioskBaseUrl();
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
