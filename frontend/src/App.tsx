import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import MainLayout from "./components/MainLayout";
import PrivateRoute from "./components/PrivateRoute";
import Reception from "./pages/Reception";
import Clinic from "./pages/Clinic";
import Lookup from "./pages/Lookup";
import Certificate from "./pages/Certificate";
import Login from "./pages/Login";
import KioskWaiting from "./pages/KioskWaiting";
import KioskAnalyze from "./pages/KioskAnalyze";
import { AuthProvider } from "./components/AuthContext";

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          {/* 첫 진입 → 로그인 */}
          <Route path="/" element={<Login />} />

          {/* 로그인한 사용자만 접근 가능 */}
          <Route element={<PrivateRoute />}>
            <Route path="/main" element={<MainLayout />}>
              <Route index element={<Reception />} />
              <Route path="clinic" element={<Clinic />} />
              <Route path="lookup" element={<Lookup />} />
              <Route path="certificate" element={<Certificate />} />
            </Route>
          </Route>

          {/* 대기실 키오스크 — 로그인 없이 접근 (인증 가드 없음) */}
          <Route path="/kiosk" element={<KioskWaiting />} />
          <Route path="/kiosk/analyze/:visitId" element={<KioskAnalyze />} />

          {/* 잘못된 경로 → 로그인으로 */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}