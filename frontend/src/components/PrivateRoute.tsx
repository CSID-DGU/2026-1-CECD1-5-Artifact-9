import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export default function PrivateRoute() {
  const { user } = useAuth();
  const location = useLocation();
  // 로그인 뒤 원래 가려던 화면으로 돌려보내려고 주소를 들고 간다(예: 대기실 모니터의 /waiting 이 세션 만료로 튕긴 경우).
  return user ? <Outlet /> : <Navigate to="/" replace state={{ from: location.pathname + location.search }} />;
}
