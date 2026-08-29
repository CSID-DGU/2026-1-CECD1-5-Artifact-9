import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { canOpen, landingPathFor, type Screen } from "../auth/roles";

/**
 * 화면 단위 직책 가드. {@link PrivateRoute}(로그인 여부) 안쪽에서 "이 직책이 이 화면을 열 수 있는가"를 본다.
 *
 * 권한이 없으면 오류 화면 대신 열 수 있는 첫 화면으로 조용히 보낸다.
 * 접수 직원이 주소창에 /main/clinic 을 쳤을 때 필요한 것은 경고가 아니라 자기 업무 화면이다.
 *
 * 다시 강조하면 이건 안내다 — 실제 차단은 서버가 한다(backend common/security 참고).
 */
export default function RoleRoute({ screen }: { screen: Screen }) {
  const { user } = useAuth();

  if (!canOpen(user?.role, screen)) {
    return <Navigate to={landingPathFor(user?.role)} replace />;
  }
  return <Outlet />;
}
