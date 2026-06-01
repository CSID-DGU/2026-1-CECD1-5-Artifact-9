import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import MainLayout from "./components/MainLayout";
import Reception from "./pages/Reception";
import Clinic from "./pages/Clinic";
import Lookup from "./pages/Lookup";
import Certificate from "./pages/Certificate";
import Login from "./pages/Login";
import { AuthProvider } from "./components/AuthContext";

export default function App() {
  return (
    <AuthProvider>
    <BrowserRouter>
      <Routes>
        {/* 사용자가 처음 접속( / )하면 로그인 페이지 호출 */}
        <Route path="/" element={<Login />} />

        {/* 로그인 성공 후 진입할 메인 레이아웃 구역 (/main) */}
        <Route path="/main" element={<MainLayout />}>
          {/* 주소창이 /main 일 때 기본 화면 Reception */}
          <Route index element={<Reception />} />
          {/* 하위 메뉴 */}
          <Route path="clinic" element={<Clinic />} />
          <Route path="lookup" element={<Lookup />} />
          <Route path="certificate" element={<Certificate />} />
        </Route>

        {/* 그 외 잘못된 주소는 접수 화면('/main')으로 리다이렉트 */}
        <Route path="*" element={<Navigate to="/main" replace />} />
      </Routes>
    </BrowserRouter>
    </AuthProvider>
  );
}