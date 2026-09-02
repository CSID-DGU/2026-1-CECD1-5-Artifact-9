import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import MainLayout from "./components/MainLayout";
import PrivateRoute from "./components/PrivateRoute";
import RoleRoute from "./components/RoleRoute";
import Reception from "./pages/Reception";
import Clinic from "./pages/Clinic";
import Lookup from "./pages/Lookup";
import Certificate from "./pages/Certificate";
import Login from "./pages/Login";
import KioskWaiting from "./pages/KioskWaiting";
import KioskAnalyze from "./pages/KioskAnalyze";
import SharedCertificate from "./pages/SharedCertificate";
import SharedVisitSummary from "./pages/SharedVisitSummary";
import { AuthProvider } from "./components/AuthContext";

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          {/* 첫 진입 → 로그인 */}
          <Route path="/" element={<Login />} />

          {/* 로그인한 사용자만 접근 가능.
              그 안에서 다시 직책별로 화면을 나눈다 — 어떤 직책이 어느 화면을 여는지는
              auth/roles.ts 의 SCREEN_MIN_ROLE 한 곳에 모여 있다.
              화면을 감추는 것은 안내일 뿐이고, 실제 차단은 서버의 @PreAuthorize 가 한다. */}
          <Route element={<PrivateRoute />}>
            <Route path="/main" element={<MainLayout />}>
              <Route element={<RoleRoute screen="reception" />}>
                <Route index element={<Reception />} />
              </Route>
              <Route element={<RoleRoute screen="clinic" />}>
                <Route path="clinic" element={<Clinic />} />
              </Route>
              <Route element={<RoleRoute screen="lookup" />}>
                <Route path="lookup" element={<Lookup />} />
              </Route>
              <Route element={<RoleRoute screen="certificate" />}>
                <Route path="certificate" element={<Certificate />} />
              </Route>
            </Route>
          </Route>

          {/* 대기실 키오스크 — 로그인 없이 접근 (인증 가드 없음).
              접수 시 발급된 토큰이 담긴 QR을 태블릿으로 찍으면 /kiosk/{token} 으로 진입한다. */}
          <Route path="/kiosk" element={<KioskWaiting />} />
          <Route path="/kiosk/:token" element={<KioskAnalyze />} />

          {/* 감열지 QR 로 들어오는 환자용 문서 열람 — 로그인 없이 접근.
              주소에 실리는 것은 문서별 열람 토큰뿐이고, 그 토큰은 해당 문서 한 건만 연다.
              경로가 짧은 것은 의도적이다 — QR 은 담는 글자가 늘수록 모듈이 촘촘해져
              감열지에서 읽기 어려워진다. */}
          <Route path="/d/c/:token" element={<SharedCertificate />} />
          <Route path="/d/v/:token" element={<SharedVisitSummary />} />

          {/* 잘못된 경로 → 로그인으로 */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}