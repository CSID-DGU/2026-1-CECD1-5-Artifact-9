import { useState } from "react";
import { Outlet, Link } from "react-router-dom";

export default function MainLayout() {
  const [isMenuOpen, setIsMenuOpen] = useState(false);

  return (
    <div className="min-h-screen bg-main-bg text-white font-sans flex flex-col relative">
      {/* 1. 공통 헤더 */}
      <header className="h-[40px] bg-blue-500 flex items-center justify-between px-4 shrink-0 z-50">
        <div className="flex items-center gap-4">
          {/* 메뉴 버튼 */}
          <div className="relative">
            <button 
              onClick={() => setIsMenuOpen(!isMenuOpen)}
              className="p-1 hover:bg-blue-600 rounded transition-colors cursor-pointer"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>
            </button>

            {/* 2. 플로팅 메뉴 패널 (너비 40px 컨셉의 메뉴바) */}
            {isMenuOpen && (
              <div className="absolute top-[35px] left-0 w-[60px] bg-black border border-gray-700 shadow-2xl flex flex-col items-center py-4 gap-6 rounded-b-lg animate-in fade-in slide-in-from-top-1">
                <Link 
                  to="/" 
                  onClick={() => setIsMenuOpen(false)}
                  className="group relative flex flex-col items-center"
                >
                  <span className="text-[10px] text-gray-400 group-hover:text-blue-400 transition-colors">접수</span>
                  {/* 호버 시 툴팁처럼 보일 수도 있고, 단순히 아이콘 대용 글자로 배치 */}
                </Link>
                <Link 
                  to="/clinic" 
                  onClick={() => setIsMenuOpen(false)}
                  className="group relative flex flex-col items-center"
                >
                  <span className="text-[10px] text-gray-400 group-hover:text-blue-400 transition-colors">진료</span>
                </Link>
              </div>
            )}
          </div>
          
        </div>
        <div className="text-sm font-medium">김철수 님</div>
      </header>

      {/* 버튼 영역 */}
      <section className="h-[35px] flex items-center justify-end px-4 shrink-0">
        {[1, 2, 3, 4, 5].map((num) => (
          <button key={num} className="h-[20px] ml-[5px] px-3 bg-gray-600 hover:bg-gray-500 text-[11px] rounded transition-colors">
            버튼{num}
          </button>
        ))}
      </section>

      {/* 클릭 시 메뉴 닫기를 위한 투명 배경 (Overlay) */}
      {isMenuOpen && (
        <div 
          className="absolute inset-0 z-40" 
          onClick={() => setIsMenuOpen(false)}
        />
      )}

      {/* 메인 콘텐츠 */}
      <main className="flex-1 overflow-hidden z-10">
        <Outlet />
      </main>
    </div>
  );
}