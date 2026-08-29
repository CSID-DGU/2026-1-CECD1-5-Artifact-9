import { STORAGE_KEYS } from "../constants";

/**
 * 로그인 세션의 단일 보관소.
 *
 * 왜 별도 파일인가. `client.ts`는 401을 받으면 "세션이 끝났다"고 알려야 하고,
 * `AuthContext`는 그 신호를 받아 로그아웃해야 한다. 그런데 `AuthContext`가 이미 `client.ts`를
 * 쓰고 있어서, `client.ts`가 거꾸로 `AuthContext`를 import하면 순환 참조가 된다.
 * 둘 다 의존할 수 있는 얇은 모듈을 하나 두어 끊는다.
 *
 * 덤으로 토큰을 꺼내는 코드가 한 곳에 모인다. 전에는 `client.ts`와 `AuthedImage.tsx`가
 * 각자 `localStorage.getItem(...)`을 호출하고 있어서, 저장 방식을 바꾸려면 두 곳을 같이 고쳐야 했다.
 */

export function getToken(): string | null {
  return localStorage.getItem(STORAGE_KEYS.TOKEN);
}

export function saveSession(token: string, user: unknown): void {
  localStorage.setItem(STORAGE_KEYS.TOKEN, token);
  localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(user));
  // 새로 로그인했으니 "이미 만료 알림을 보냈다"는 표시를 푼다.
  expiryNotified = false;
}

export function loadUser<T>(): T | null {
  const saved = localStorage.getItem(STORAGE_KEYS.USER);
  if (!saved) return null;
  try {
    return JSON.parse(saved) as T;
  } catch {
    // 저장된 값이 깨졌으면 로그인 안 한 것으로 친다. 여기서 던지면 앱이 아예 못 뜬다.
    localStorage.removeItem(STORAGE_KEYS.USER);
    return null;
  }
}

export function clearSession(): void {
  localStorage.removeItem(STORAGE_KEYS.TOKEN);
  localStorage.removeItem(STORAGE_KEYS.USER);
}

/* ------------------------------------------------------------------ */
/* 세션 만료 신호                                                       */
/* ------------------------------------------------------------------ */

type SessionExpiredHandler = () => void;

let handler: SessionExpiredHandler | null = null;
let expiryNotified = false;

/** `AuthProvider`가 마운트되면서 자기 로그아웃 함수를 등록한다. */
export function setSessionExpiredHandler(next: SessionExpiredHandler | null): void {
  handler = next;
}

/**
 * 토큰이 만료·위조로 거부됐을 때 호출한다. 세션을 지우고 등록된 핸들러를 한 번만 부른다.
 *
 * 한 번만 부르는 이유. 화면 하나가 진료기록·이미지·분석결과를 병렬로 요청하는데,
 * 토큰이 만료되면 이들이 동시에 401로 떨어진다. 매번 알리면 "세션이 만료됐습니다"가
 * 대여섯 번 겹쳐 뜨고 라우터 이동도 그만큼 중복된다. 다음 로그인 성공 시 {@link saveSession}이 푼다.
 */
export function notifySessionExpired(): void {
  if (expiryNotified) return;
  expiryNotified = true;
  clearSession();
  handler?.();
}
