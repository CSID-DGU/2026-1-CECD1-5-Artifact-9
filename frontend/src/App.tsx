import { BrowserRouter, Routes, Route } from "react-router-dom";
import MainLayout from "./components/MainLayout";
import Reception from "./pages/Reception";
import Clinic from "./pages/Clinic";
import Lookup from "./pages/Lookup";
import Certificate from "./pages/Certificate";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<Reception />} />
          <Route path="clinic" element={<Clinic />} />
          <Route path="lookup" element={<Lookup />} />
          <Route path="certificate" element={<Certificate />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}