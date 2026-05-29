import { Outlet, Link } from "react-router-dom";

export default function MainLayout() {

  return (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col relative">
      {/* 공통 헤더 */}
      <header className="h-10 bg-blue-500 flex items-center justify-between px-4 shrink-0 z-50">
        <div className="flex items-center gap-4">
            <Link 
              to="/" 
              className="group relative flex flex-col items-center"
            >
              <span className="group-hover:text-blue-400 transition-colors ml-2">접수</span>
            </Link>
            <Link
              to="/clinic"
              className="group relative flex flex-col items-center"
            >
              <span className="group-hover:text-blue-400 transition-colors">진료</span>
            </Link>
            <Link
              to="/lookup"
              className="group relative flex flex-col items-center"
            >
              <span className="group-hover:text-blue-400 transition-colors">조회</span>
            </Link>
            <Link
              to="/certificate"
              className="group relative flex flex-col items-center"
            >
              <span className="group-hover:text-blue-400 transition-colors">증명</span>
            </Link>
        </div>
        <div>AI 보조 진단 시스템</div>
      </header>

      {/* 메인 콘텐츠 화면 */}
      <main className="flex-1 overflow-hidden z-10">
        <Outlet />
      </main>
    </div>
  );
}