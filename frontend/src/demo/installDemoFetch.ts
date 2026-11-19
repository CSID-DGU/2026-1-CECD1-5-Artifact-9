import { endDemo, isDemoActive } from "./demoMode";

/**
 * 데모일 때만 /api 요청을 이 탭 안의 가짜 백엔드로 돌린다(앱 시작 때 한 번 설치).
 *
 * 화면·API 코드는 그대로 두고 fetch 한 곳만 가로챈다 — apiRequest, AuthedImage 등이 모두 fetch 를 쓴다.
 * 데모가 아니면 원래 fetch 를 그대로 부른다. 가짜 백엔드 코드는 데모를 처음 쓸 때 따로 받아 온다(코드 분할).
 * 로그인·가입 요청은 데모를 끝내고 실제 서버로 보낸다 — 데모 탭에서 직원이 실제로 로그인하는 경우다.
 */
export function installDemoFetch(): void {
  const w = window as Window & { __artifactDemoFetch?: boolean };
  if (w.__artifactDemoFetch) return;
  w.__artifactDemoFetch = true;
  const original = window.fetch.bind(window);

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (!isDemoActive()) return original(input, init);
    const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw, window.location.origin);
    if (url.origin !== window.location.origin || !url.pathname.startsWith("/api/")) return original(input, init);
    if (url.pathname.startsWith("/api/v1/auth/")) {
      endDemo();
      return original(input, init);
    }
    const { handleDemoRequest } = await import("./backend");
    return handleDemoRequest(url, init);
  };
}
