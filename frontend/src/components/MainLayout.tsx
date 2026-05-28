import { useState } from "react";
import { Outlet, Link } from "react-router-dom";

export default function MainLayout() {
  const [isMenuOpen, setIsMenuOpen] = useState(false);

  return (
    <div className="min-h-screen bg-main-bg text-white font-sans flex flex-col relative">
      {/* 공통 헤더 */}
      <header className="h-[40px] bg-blue-500 flex items-center justify-between px-4 shrink-0 z-50">
        <div className="flex items-center gap-4">
          {/* 메뉴 아이콘 */}
          <div className="relative">
            <button 
              onClick={() => setIsMenuOpen(!isMenuOpen)}
              className="p-1 hover:bg-blue-600 rounded transition-colors cursor-pointer"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>
            </button>

            {/* 메뉴 패널 */}
            {isMenuOpen && (
              <div className="absolute top-[35px] left-0 w-[60px] bg-black border border-gray-700 shadow-2xl flex flex-col items-center py-4 gap-6 rounded-b-lg animate-in fade-in slide-in-from-top-1">
                <Link 
                  to="/" 
                  onClick={() => setIsMenuOpen(false)}
                  className="group relative flex flex-col items-center"
                >
                  <span className="text-[10px] text-gray-400 group-hover:text-blue-400 transition-colors">접수</span>
                </Link>
                <Link
                  to="/clinic"
                  onClick={() => setIsMenuOpen(false)}
                  className="group relative flex flex-col items-center"
                >
                  <span className="text-[10px] text-gray-400 group-hover:text-blue-400 transition-colors">진료</span>
                </Link>
                <Link
                  to="/lookup"
                  onClick={() => setIsMenuOpen(false)}
                  className="group relative flex flex-col items-center"
                >
                  <span className="text-[10px] text-gray-400 group-hover:text-blue-400 transition-colors">조회</span>
                </Link>
                <Link
                  to="/certificate"
                  onClick={() => setIsMenuOpen(false)}
                  className="group relative flex flex-col items-center"
                >
                  <span className="text-[10px] text-gray-400 group-hover:text-blue-400 transition-colors">증명</span>
                </Link>
              </div>
            )}
          </div>
          
        </div>
        <div className="text-sm font-medium text-blue-100">AI 보조 진단 시스템</div>
      </header>

      {/* 클릭 시 메뉴 닫기 */}
      {isMenuOpen && (
        <div 
          className="absolute inset-0 z-40" 
          onClick={() => setIsMenuOpen(false)}
        />
      )}

      {/* 메인 콘텐츠 화면 */}
      <main className="flex-1 overflow-hidden z-10">
        <Outlet />
      </main>
    </div>
  );
}