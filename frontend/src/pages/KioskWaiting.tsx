import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getKioskPending } from "../api/kiosk";
import { Card } from "../components/Card";

/**
 * 대기실 태블릿이 상시 띄워두는 화면. 로그인 없이 접근 가능(라우팅에서 인증 가드 제외).
 * 3초 간격으로 새로 접수된 환자를 폴링하다가, 감지되면 해당 환자의 분석 페이지로 이동한다.
 */
export default function KioskWaiting() {
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    let inFlight = false;

    const interval = setInterval(async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const pending = await getKioskPending();
        if (!cancelled) navigate(`/kiosk/analyze/${pending.visitId}`);
      } catch {
        // 대기 중인 환자 없음 — 계속 폴링
      } finally {
        inFlight = false;
      }
    }, 3000);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [navigate]);

  return (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col">
      <header className="h-10 bg-blue-500 flex items-center px-4 shrink-0">
        <span className="text-blue-200 text-xs">AI 보조 진단 시스템 · 대기실 키오스크</span>
      </header>

      <main className="flex flex-1 items-center justify-center p-6">
        <Card title="대기 화면" className="w-full max-w-md" contentClassName="!p-6">
          <div className="flex flex-col items-center gap-3 py-6 text-center">
            <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-600 border-t-blue-500" />
            <p className="text-base font-semibold text-white">환자를 기다리는 중입니다</p>
            <p className="text-xs text-gray-400">접수가 완료되면 자동으로 분석 화면으로 이동합니다.</p>
          </div>
        </Card>
      </main>
    </div>
  );
}
