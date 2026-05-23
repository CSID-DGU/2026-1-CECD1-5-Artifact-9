import { BrowserRouter, Routes, Route } from "react-router-dom";
import MainLayout from "./components/MainLayout";
import Reception from "./pages/Reception";
import Clinic from "./pages/Clinic";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* MainLayout이 감싸고 있는 모든 경로는 헤더를 공유합니다 */}
        <Route path="/" element={<MainLayout />}>
          {/* index는 부모 경로("/")와 일치할 때 보여줄 기본 페이지입니다 */}
          <Route index element={<Reception />} />
          <Route path="clinic" element={<Clinic />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}