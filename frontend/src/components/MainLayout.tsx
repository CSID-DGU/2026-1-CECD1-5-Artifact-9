import { Outlet, Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { canOpen, type Screen } from "../auth/roles";

/** 헤더 탭 — 직책이 열 수 없는 화면은 아예 보여주지 않는다. 순서가 곧 노출 순서다. */
const TABS: { screen: Screen; path: string; label: string }[] = [
  { screen: "reception", path: "/main", label: "접수" },
  { screen: "clinic", path: "/main/clinic", label: "진료" },
  { screen: "lookup", path: "/main/lookup", label: "조회" },
  { screen: "certificate", path: "/main/certificate", label: "증명" },
];

export default function MainLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate("/");
  };

  return (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col relative">
      {/* 공통 헤더 */}
      <header className="h-10 bg-blue-500 flex items-center justify-between px-4 shrink-0 z-50">
        <div className="flex items-center gap-4">
          {TABS.filter((tab) => canOpen(user?.role, tab.screen)).map((tab, index) => (
            <Link
              key={tab.path}
              to={tab.path}
              className="group relative flex flex-col items-center"
            >
              <span
                className={`group-hover:text-blue-400 transition-colors${index === 0 ? " ml-2" : ""}`}
              >
                {tab.label}
              </span>
            </Link>
          ))}
        </div>

        {/* 로그인 정보 */}
        <div className="flex items-center gap-4 text-xs">
          {user ? (
            <div className="flex items-center gap-3">
              <span>
                <strong className="text-white font-bold">{user.name}</strong> 님
              </span>
              <button
                onClick={handleLogout}
                className="bg-blue-600 hover:bg-blue-700 border border-blue-400/30 px-2 py-0.5 rounded text-[11px] cursor-pointer transition-colors"
              >
                로그아웃
              </button>
            </div>
          ) : (
            <span className="text-blue-200">로그인이 필요합니다.</span>
          )}
          <span className="text-blue-200 border-l border-blue-400 pl-3 hidden sm:inline">
            AI 보조 진단 시스템
          </span>
        </div>
      </header>

      {/* 메인 콘텐츠 화면 */}
      <main className="flex-1 overflow-hidden z-10">
        <Outlet />
      </main>
    </div>
  );
}
