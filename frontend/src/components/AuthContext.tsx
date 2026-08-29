import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { apiRequest } from "../api/client";
import { getErrorMessage } from "../api/errors";
import {
  clearSession,
  loadUser,
  saveSession,
  setSessionExpiredHandler,
} from "../api/session";
import {
  AuthContext,
  type AuthResult,
  type Member,
  type SignupPayload,
} from "../auth/AuthContext";

/**
 * 로그인 상태를 앱 전체에 공급한다.
 *
 * 이 파일은 컴포넌트만 내보낸다. `useAuth`와 타입은 `auth/AuthContext.ts`에 있다 —
 * 섞어 두면 Fast Refresh가 꺼져서 화면 수정마다 로그인이 풀린다(그쪽 주석 참고).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Member | null>(() => loadUser<Member>());
  const [sessionExpired, setSessionExpired] = useState(false);

  const logout = useCallback(() => {
    clearSession();
    setUser(null);
  }, []);

  /**
   * 토큰이 만료·위조로 거부되면(401) 저장된 로그인 상태를 지운다.
   *
   * 이게 없으면 토큰만 죽고 화면은 로그인된 척한다. localStorage에 사용자 정보가
   * 남아 있으니 메뉴도 이름도 그대로인데, 누르는 것마다 조용히 실패한다.
   * JWT 만료(개발 24시간 / 운영 8시간)는 드문 일이 아니라 매일 일어나는 일이다.
   *
   * user가 null이 되면 PrivateRoute가 로그인 화면으로 보낸다 — 여기서 직접 이동시키지 않는다.
   */
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setUser(null);
      setSessionExpired(true);
    });
    return () => setSessionExpiredHandler(null);
  }, []);

  const authenticate = async (path: string, payload: unknown): Promise<AuthResult> => {
    try {
      const res = await apiRequest<Member & { token: string }>(path, {
        method: "POST",
        body: JSON.stringify(payload),
      });
      const { token, ...member } = res;
      saveSession(token, member);
      setUser(member);
      setSessionExpired(false);
      return { ok: true };
    } catch (error) {
      return { ok: false, message: getErrorMessage(error) };
    }
  };

  const login = (loginId: string, password: string) =>
    authenticate("/api/v1/auth/login", { loginId, password });

  const signup = (payload: SignupPayload) =>
    authenticate("/api/v1/auth/signup", payload);

  return (
    <AuthContext.Provider value={{ user, sessionExpired, login, signup, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
