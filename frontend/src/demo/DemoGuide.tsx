import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { markProgress, readSnapshot } from "./backend";
import { DEMO_CHANGE_EVENT, endDemo, isDemoActive, resetDemoData } from "./demoMode";

const OPEN_KEY = "artifact_demo_guide_open";

function readOpen(): boolean {
  try {
    const saved = sessionStorage.getItem(OPEN_KEY);
    if (saved !== null) return saved === "1";
  } catch {
    /* 무시 */
  }
  return window.innerWidth >= 768; // 키오스크(휴대폰) 화면에서는 접어 둔 채 시작한다
}

type Step = { key: string; title: string; done: boolean; hint: string; action: { label: string; to: string } | null };

/**
 * 데모 체험 안내 — 화면 왼쪽 아래에 작게 떠서 지금 할 일 하나와 이동 버튼을 보여 준다(전체 단계는 펼쳐 본다).
 * 화면을 덜 가리려고 평소에는 지금 단계만 보인다.
 * 진행은 가짜 백엔드의 기록(체험자가 접수한 내원의 예비분석·분석·처방·증명서)을 보고 저절로 체크한다.
 * 기존 화면은 그대로 두고 그 위에 얹기만 한다.
 */
export default function DemoGuide() {
  const navigate = useNavigate();
  const location = useLocation();
  const [snap, setSnap] = useState(() => readSnapshot());
  const [open, setOpen] = useState(readOpen);
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    const refresh = () => setSnap(readSnapshot());
    window.addEventListener(DEMO_CHANGE_EVENT, refresh);
    return () => window.removeEventListener(DEMO_CHANGE_EVENT, refresh);
  }, []);

  // 조회 화면·대기실 현황판은 열어 보면 그 단계를 마친 것으로 본다.
  const visitDone = snap.visit?.status === "COMPLETED";
  useEffect(() => {
    if (location.pathname === "/main/lookup" && visitDone) markProgress({ lookupSeen: true });
    if (location.pathname === "/waiting" && snap.visit) markProgress({ waitingSeen: true });
  }, [location.pathname, visitDone, snap.visit]);

  if (!isDemoActive()) return null;

  const toggle = () => {
    const next = !open;
    setOpen(next);
    try {
      sessionStorage.setItem(OPEN_KEY, next ? "1" : "0");
    } catch {
      /* 무시 */
    }
  };

  const v = snap.visit;
  const who = v ? `${snap.patientName ?? ""} (V${String(v.id).padStart(5, "0")})` : "방금 접수한 환자";
  const steps: Step[] = [
    {
      key: "reception", title: "접수", done: !!v,
      hint: "접수 화면에서 '신규 등록'에 이름을 넣고 '신규 환자 접수'를 누르세요. 가상 환자(예: 이준호)를 검색해 '기존 환자 접수'로 해도 됩니다. 접수하면 키오스크 QR이 나타납니다.",
      action: { label: "접수 화면으로", to: "/main" },
    },
    {
      key: "kiosk", title: "키오스크 사전 분석", done: snap.hasPreliminary,
      hint: "환자가 대기실에서 접수증 QR로 여는 휴대폰 화면입니다. 데모는 이 탭 안에서만 돌아 QR 대신 아래 버튼으로 엽니다. '예, 시작하기' → 촬영 대상 → '파일 선택'에서 사진을 고르고 '분석하기' → 결과를 본 뒤 '확인'.",
      action: v?.kioskToken && v.status !== "COMPLETED" && v.status !== "CANCELLED" ? { label: "키오스크 열기", to: `/kiosk/${v.kioskToken}` } : null,
    },
    {
      key: "clinic", title: "진료 · AI 분석", done: snap.hasAnalysis,
      hint: `진료 화면의 진료대기에서 ${who}을(를) 누르고 '진료 시작' → '파일 선택'으로 사진 고르기 → '업로드' → 'AI 분석'. 키오스크 결과도 왼쪽 아래에 함께 보입니다.`,
      action: { label: "진료 화면으로", to: "/main/clinic" },
    },
    {
      key: "prescribe", title: "진단 · 처방 · 진료 완료", done: visitDone,
      hint: "'상병코드 검색'(예: 여드름) → '약품 검색'(예: 아다팔렌)으로 고르고 용법을 적은 뒤 'AI 처방 코멘트 생성' → '처방 저장' → '진료 완료'.",
      action: { label: "진료 화면으로", to: "/main/clinic" },
    },
    {
      key: "lookup", title: "진료 이력 조회", done: snap.progress.lookupSeen,
      hint: "조회 화면에서 환자 이름으로 검색해 내원 타임라인·사진·AI 분석·처방을 확인하세요.",
      action: { label: "조회 화면으로", to: "/main/lookup" },
    },
    {
      key: "certificate", title: "증명서 발급 · 인쇄", done: snap.hasCertificate,
      hint: "증명서 화면에서 환자 → 내원을 고르고 '진단서' → 'AI 초안' → '발급' → '인쇄'(또는 PDF 저장). 처방전·진료의뢰서도 같은 방식입니다.",
      action: { label: "증명서 화면으로", to: "/main/certificate" },
    },
    {
      key: "waiting", title: "대기실 현황판 (선택)", done: snap.progress.waitingSeen,
      hint: "로비 모니터에 띄우는 대기 순서·진료실 호출 화면입니다. 진료를 시작하면 '진료실로 방문해 주세요'가 뜹니다.",
      action: { label: "현황판 열기", to: "/waiting" },
    },
  ];
  // 키오스크처럼 건너뛴 단계가 있어도 이미 지난 단계 뒤에서 이어 간다.
  const lastDone = steps.reduce((acc, s, i) => (s.done ? i : acc), -1);
  const currentIndex = steps.findIndex((s, i) => !s.done && i > lastDone);
  const doneCount = steps.filter((s) => s.done).length;

  const current = currentIndex >= 0 ? steps[currentIndex] : null;

  return (
    <div className="fixed bottom-3 left-3 z-[60] w-[280px] max-w-[calc(100vw-24px)] text-xs">
      {!open ? (
        <button
          type="button"
          onClick={toggle}
          className="rounded-full border border-blue-400/60 bg-gray-900/95 px-3 py-2 font-semibold text-blue-100 shadow-lg"
        >
          데모 가이드 {doneCount}/{steps.length}{current ? ` · ${current.title}` : ""} ▲
        </button>
      ) : (
        <div role="complementary" aria-label="데모 체험 가이드" className="overflow-hidden rounded-xl border border-blue-400/50 bg-gray-900/95 shadow-2xl">
          <div className="flex items-center justify-between bg-blue-600 px-3 py-1.5">
            <span className="font-bold text-white">데모 체험 가이드 · {doneCount}/{steps.length}</span>
            <span className="flex gap-2">
              <button type="button" onClick={() => setShowAll((x) => !x)} className="text-[11px] text-blue-100 hover:text-white">
                {showAll ? "단계 접기" : "전체 단계"}
              </button>
              <button type="button" onClick={toggle} className="text-[11px] text-blue-100 hover:text-white">접기 ▼</button>
            </span>
          </div>

          {/* 진행 점 — 끝난 단계 초록, 지금 단계 파랑 */}
          <div className="flex items-center gap-1 px-3 pt-2" aria-hidden="true">
            {steps.map((s, i) => (
              <span key={s.key} className={`h-1.5 flex-1 rounded-full ${s.done ? "bg-emerald-500" : i === currentIndex ? "bg-blue-500" : "bg-gray-700"}`} />
            ))}
          </div>

          {showAll && (
            <ol className="max-h-[30vh] overflow-y-auto border-b border-gray-700 px-3 py-1.5">
              {steps.map((s, i) => (
                <li key={s.key} className="flex items-center gap-2 py-0.5">
                  <span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[10px] font-bold ${
                    s.done ? "bg-emerald-500 text-white" : i === currentIndex ? "bg-blue-500 text-white" : "bg-gray-700 text-gray-300"
                  }`}>
                    {s.done ? "✓" : i + 1}
                  </span>
                  <span className={s.done ? "text-gray-400" : i === currentIndex ? "font-bold text-white" : "text-gray-300"}>
                    {s.title}{!s.done && i < lastDone ? " (건너뜀)" : ""}
                  </span>
                </li>
              ))}
            </ol>
          )}

          <div className="px-3 py-2">
            {current ? (
              <>
                <p className="font-bold text-white">{currentIndex + 1}. {current.title}</p>
                <p className="mt-0.5 leading-relaxed text-gray-200">{current.hint}</p>
                {current.action && (
                  <button
                    type="button"
                    onClick={() => navigate(current.action!.to)}
                    className="mt-1.5 rounded bg-blue-600 px-2.5 py-1 font-semibold text-white hover:bg-blue-500"
                  >
                    {current.action.label} →
                  </button>
                )}
              </>
            ) : (
              <p className="leading-relaxed text-emerald-200">체험을 모두 마쳤습니다. 다른 사진이나 가상 환자로 다시 해 보셔도 됩니다.</p>
            )}
          </div>

          <div className="flex items-center gap-3 border-t border-gray-700 px-3 py-1.5 text-[11px]">
            <span className="flex-1 text-gray-500">가상 데이터 · 실제 서버와 연결 안 됨</span>
            <button
              type="button"
              onClick={() => { resetDemoData(); window.location.assign("/main"); }}
              className="text-gray-300 underline hover:text-white"
            >
              처음부터
            </button>
            <button
              type="button"
              onClick={() => { endDemo(); window.location.assign("/"); }}
              className="text-red-300 underline hover:text-red-200"
            >
              데모 종료
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
