import { createContext, useContext } from "react";

/**
 * 로그인 상태 컨텍스트의 타입과 훅.
 *
 * <p><b>왜 Provider와 파일을 나눴나.</b> 한 파일이 컴포넌트(`AuthProvider`)와
 * 컴포넌트가 아닌 것(`useAuth`, 타입)을 함께 내보내면 Vite의 Fast Refresh가 동작하지 않는다
 * (eslint `react-refresh/only-export-components`). 화면을 고칠 때마다 앱 전체가 새로고침되고
 * 로그인 상태까지 날아가서, 개발 중에 매번 다시 로그인해야 한다.
 *
 * <p>컴포넌트는 `components/AuthContext.tsx`에, 나머지는 여기에 둔다.
 */

export interface Member {
  memberId: number;
  loginId: string;
  name: string;
  role: string;
}

export type SignupPayload = {
  loginId: string;
  password: string;
  name: string;
  role?: string;
  licenseNumber?: string | null;
  department?: string | null;
};

/**
 * 로그인/회원가입 결과.
 *
 * <p>전에는 `Promise<boolean>`이라 <b>실패한 이유가 버려졌다.</b> 서버는
 * "이미 사용 중인 아이디입니다" 같은 정확한 문구를 주는데, 화면에는
 * "회원가입에 실패했습니다. 아이디 중복 또는 입력값을 확인하세요."라는 추측성 안내가 떴다.
 * 이제 실패 사유를 그대로 올려보내 화면이 그걸 보여줄 수 있게 한다.
 */
export type AuthResult = { ok: true } | { ok: false; message: string };

export interface AuthContextType {
  user: Member | null;
  /** 토큰 만료로 강제 로그아웃된 직후인지. 로그인 화면이 안내 문구를 띄우는 데 쓴다. */
  sessionExpired: boolean;
  login: (loginId: string, password: string) => Promise<AuthResult>;
  signup: (payload: SignupPayload) => Promise<AuthResult>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within an AuthProvider");
  return context;
}
