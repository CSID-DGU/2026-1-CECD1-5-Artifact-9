import { useEffect, useRef, useState } from "react";

import { getErrorMessage } from "../api/errors";
import { getPatient } from "../api/patients";
import { listVisitsByDate, type Visit, type VisitStatus } from "../api/visits";
import { ArtifactMark } from "../components/ArtifactLogo";
import { formatVisitNo } from "../utils/visitNo";

/**
 * 대기실(로비) 모니터용 진료 대기 현황판 — /waiting
 *
 * 병원 로비 모니터처럼 오늘 접수한 환자의 대기번호(접수증에 크게 찍히는 접수번호 V00056)와 이름,
 * 대기 순서를 보여 주고, 의사가 진료를 시작하면 "○○○ 님, 진료실로 방문해 주세요"를 화면 가득 띄운다.
 *
 * - 대기: 접수(RECEIVED). 키오스크 촬영·예비분석은 내원 상태를 바꾸지 않으므로, 의사가 부르기 전까지는 대기다.
 * - 진료실: 진료 시작(IN_PROGRESS)부터 처방까지. 진료실 AI 분석 뒤 '분석완료'가 돼도 대기로 돌아가지 않는다
 *   (진료 시작은 접수 상태에서만 할 수 있어서 상태만으로 갈린다 — backend Visit.startConsultation).
 * - 진료 완료·취소는 화면에서 빠진다.
 *
 * 로비에 공개되는 화면이라 이름 가운데 글자를 가린다(김*섭). 내원 목록이 직원 권한 API 라 로그인한 브라우저에서만
 * 열리고, 상단 메뉴 없이 화면 전체를 쓴다. 갱신은 3초마다 목록을 다시 읽는 방식이다.
 */

const POLL_MS = 3000;
const CALL_SHOW_MS = 9000;
const MAX_WAITING_ROWS = 8;

const IN_ROOM = new Set<VisitStatus>(["IN_PROGRESS", "ANALYZING", "ANALYZED", "DIAGNOSED", "PRESCRIBED"]);
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"] as const;

type Call = { visitId: number; visitNo: string; name: string };

/** 김경섭 → 김*섭, 박서 → 박*, 남궁민수 → 남**수 */
function maskName(name: string): string {
  const chars = [...name.trim()];
  if (chars.length <= 1) return chars.join("");
  if (chars.length === 2) return `${chars[0]}*`;
  return `${chars[0]}${"*".repeat(chars.length - 2)}${chars[chars.length - 1]}`;
}

/** 브라우저(부스 노트북) 날짜 기준 오늘 — 서버의 내원일 조회 형식(yyyy-MM-dd). */
function localDateKey(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

function formatClock(d: Date): { date: string; time: string } {
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    date: `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} (${WEEKDAYS[d.getDay()]})`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
  };
}

export default function WaitingBoard() {
  const [visits, setVisits] = useState<Visit[]>([]);
  const [names, setNames] = useState<Record<number, string>>({});
  /** 진료실에 들어간 순서. 처음 열었을 때 이미 들어가 있던 내원은 0 이다. */
  const [calledAt, setCalledAt] = useState<Record<number, number>>({});
  const [announcing, setAnnouncing] = useState<Call | null>(null);
  const [now, setNow] = useState(() => new Date());
  const [error, setError] = useState<string | null>(null);
  const [isFullscreen, setIsFullscreen] = useState(false);

  const namesRef = useRef<Record<number, string>>({});
  /** 직전 갱신의 상태. null 이면 아직 한 번도 읽지 않았다 — 첫 화면에서는 지난 호출을 다시 띄우지 않는다. */
  const lastStatusRef = useRef<Map<number, VisitStatus> | null>(null);
  const queueRef = useRef<Call[]>([]);
  const showingRef = useRef(false);
  const busyRef = useRef(false);

  useEffect(() => {
    document.title = "진료 대기 현황";
  }, []);

  useEffect(() => {
    let stopped = false;
    let showTimer: number | undefined;

    /** 호출을 하나씩 9초 동안 띄운다. 여러 명이 한꺼번에 불리면 차례로 띄운다. */
    function pump() {
      if (stopped || showingRef.current) return;
      const next = queueRef.current.shift();
      if (!next) return;
      showingRef.current = true;
      setAnnouncing(next);
      showTimer = window.setTimeout(() => {
        showingRef.current = false;
        setAnnouncing(null);
        pump();
      }, CALL_SHOW_MS);
    }

    async function tick() {
      if (busyRef.current) return; // 앞 갱신이 아직 안 끝났으면 건너뛴다(느린 망에서 요청이 쌓이지 않게)
      busyRef.current = true;
      try {
        const list = await listVisitsByDate(localDateKey(new Date()));
        const missing = [...new Set(list.map((v) => v.patientId))].filter((id) => !(id in namesRef.current));
        if (missing.length > 0) {
          const pairs = await Promise.all(
            missing.map((id) =>
              getPatient(id).then(
                (p) => [id, p.name] as const,
                () => [id, ""] as const,
              ),
            ),
          );
          for (const [id, name] of pairs) namesRef.current[id] = name;
        }
        if (stopped) return;

        const previous = lastStatusRef.current;
        const stamp = Date.now();
        // 대기 → 진료실로 바뀐 내원(갱신 사이에 접수부터 진료 시작까지 끝난 것도 포함)
        const newlyCalled =
          previous === null
            ? []
            : list.filter((v) => IN_ROOM.has(v.status) && !IN_ROOM.has(previous.get(v.id) ?? "RECEIVED"));
        lastStatusRef.current = new Map(list.map((v) => [v.id, v.status]));

        setNames({ ...namesRef.current });
        setVisits(list);
        setCalledAt((prev) => {
          const next = { ...prev };
          for (const v of list) {
            if (IN_ROOM.has(v.status) && next[v.id] === undefined) next[v.id] = previous === null ? 0 : stamp;
          }
          return next;
        });
        setError(null);

        for (const v of [...newlyCalled].sort((a, b) => a.id - b.id)) {
          queueRef.current.push({
            visitId: v.id,
            visitNo: formatVisitNo(v.id),
            name: maskName(namesRef.current[v.patientId] ?? ""),
          });
        }
        pump();
      } catch (e) {
        if (!stopped) setError(getErrorMessage(e));
      } finally {
        busyRef.current = false;
      }
    }

    void tick();
    const poll = window.setInterval(() => void tick(), POLL_MS);
    return () => {
      stopped = true;
      window.clearInterval(poll);
      window.clearTimeout(showTimer);
      showingRef.current = false;
    };
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const onChange = () => setIsFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  const nameOf = (v: Visit) => maskName(names[v.patientId] ?? "");
  const waiting = visits
    .filter((v) => v.status === "RECEIVED")
    .sort((a, b) => a.visitDate.localeCompare(b.visitDate) || a.id - b.id);
  const inRoom = visits
    .filter((v) => IN_ROOM.has(v.status))
    .sort((a, b) => (calledAt[b.id] ?? 0) - (calledAt[a.id] ?? 0) || b.id - a.id);
  const current = inRoom[0];
  const alsoInRoom = inRoom.slice(1);
  const clock = formatClock(now);

  return (
    <div className="fixed inset-0 flex select-none flex-col overflow-hidden bg-[#0b1d3a] text-white">
      {/* 머리글 */}
      <header className="flex items-center justify-between border-b border-white/10 bg-[#081630] px-[3vw] py-[1.8vh]">
        <div className="flex items-center gap-[1.2vw]">
          <ArtifactMark className="text-sky-300" size={48} />
          <h1 className="text-[2.4vw] font-bold tracking-tight">진료 대기 현황</h1>
        </div>
        <div className="flex items-baseline gap-[1.2vw] tabular-nums">
          <span className="text-[1.5vw] text-white/70">{clock.date}</span>
          <span className="text-[2.6vw] font-semibold">{clock.time}</span>
        </div>
      </header>

      <main className="grid min-h-0 flex-1 grid-cols-[1fr_1.25fr] gap-[2vw] px-[3vw] py-[3vh]">
        {/* 진료실 호출 */}
        <section className="flex min-h-0 flex-col rounded-[1.2vw] border border-white/10 bg-white/[0.04] p-[2vw]">
          <h2 className="text-[1.6vw] font-semibold text-sky-200">진료실 호출</h2>
          {current ? (
            <div className="mt-[2vh] flex flex-1 flex-col items-center justify-center rounded-[1vw] border-[0.25vw] border-amber-300/80 bg-amber-300/[0.07] px-[2vw] py-[3vh] text-center">
              <div className="text-[6vw] font-black leading-none tracking-wider text-amber-300 tabular-nums">
                {formatVisitNo(current.id)}
              </div>
              {nameOf(current) && <div className="mt-[2.5vh] text-[3.4vw] font-bold">{nameOf(current)} 님</div>}
              <div className="mt-[1.5vh] text-[2.2vw] text-white/90">진료실로 방문해 주세요</div>
            </div>
          ) : (
            <div className="mt-[2vh] flex flex-1 items-center justify-center rounded-[1vw] border border-dashed border-white/15 text-[1.8vw] text-white/50">
              곧 다음 분을 모시겠습니다
            </div>
          )}
          {alsoInRoom.length > 0 && (
            <div className="mt-[2vh] text-[1.4vw] text-white/70">
              진료 중
              {alsoInRoom.map((v) => (
                <span key={v.id} className="ml-[1vw] tabular-nums text-white">
                  {formatVisitNo(v.id)} {nameOf(v)}
                </span>
              ))}
            </div>
          )}
        </section>

        {/* 대기 순서 */}
        <section className="flex min-h-0 flex-col rounded-[1.2vw] border border-white/10 bg-white/[0.04] p-[2vw]">
          <div className="flex items-baseline justify-between">
            <h2 className="text-[1.6vw] font-semibold text-sky-200">대기 순서</h2>
            <span className="text-[1.4vw] text-white/70">
              대기 <b className="text-white">{waiting.length}</b>명
            </span>
          </div>
          <div className="mt-[1.5vh] grid grid-cols-[6vw_1fr_1fr_10vw] border-b border-white/15 pb-[1vh] text-[1.2vw] text-white/55">
            <span>순서</span>
            <span>대기번호</span>
            <span>이름</span>
            <span className="text-right">상태</span>
          </div>
          {waiting.length === 0 ? (
            <div className="flex flex-1 items-center justify-center text-[1.8vw] text-white/50">
              대기 중인 분이 없습니다
            </div>
          ) : (
            <ol className="flex min-h-0 flex-1 flex-col">
              {waiting.slice(0, MAX_WAITING_ROWS).map((v, i) => (
                <li
                  key={v.id}
                  className={`grid grid-cols-[6vw_1fr_1fr_10vw] items-center border-b border-white/[0.07] py-[1.3vh] ${
                    i === 0 ? "rounded-[0.6vw] bg-sky-400/15 px-[0.6vw]" : "px-[0.6vw]"
                  }`}
                >
                  <span className="text-[2vw] text-white/70 tabular-nums">{i + 1}</span>
                  <span className="text-[2.5vw] font-bold tabular-nums">{formatVisitNo(v.id)}</span>
                  <span className="text-[2.2vw]">{nameOf(v)}</span>
                  <span className="text-right">
                    <span
                      className={`rounded-full px-[0.9vw] py-[0.4vh] text-[1.2vw] ${
                        i === 0 ? "bg-sky-300 font-semibold text-[#0b1d3a]" : "bg-white/10 text-white/80"
                      }`}
                    >
                      {i === 0 ? "다음 순서" : "대기"}
                    </span>
                  </span>
                </li>
              ))}
              {waiting.length > MAX_WAITING_ROWS && (
                <li className="py-[1.3vh] text-center text-[1.4vw] text-white/60">
                  외 {waiting.length - MAX_WAITING_ROWS}명 대기 중
                </li>
              )}
            </ol>
          )}
        </section>
      </main>

      <footer className="flex items-center justify-between bg-[#081630] px-[3vw] py-[1.6vh] text-[1.2vw] text-white/70">
        <span>접수증의 대기번호를 확인해 주세요 · 호출되면 진료실로 와 주세요 · 개인정보 보호를 위해 이름 일부를 가렸습니다</span>
        {error && <span className="text-amber-300">연결이 끊겨 다시 연결하는 중입니다</span>}
      </footer>

      {!isFullscreen && (
        <button
          type="button"
          onClick={() => void document.documentElement.requestFullscreen?.()}
          className="absolute right-[1vw] top-[11vh] rounded border border-white/20 bg-white/10 px-3 py-1 text-xs text-white/80 hover:bg-white/20"
        >
          전체 화면
        </button>
      )}

      {/* 호출 — 진료가 시작되는 순간 화면 가득 9초 */}
      {announcing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#04101f]/85" role="alert" aria-live="assertive">
          <div className="flex flex-col items-center rounded-[2vw] border-[0.5vw] border-amber-300 bg-[#0b1d3a] px-[7vw] py-[6vh] text-center shadow-[0_0_6vw_rgba(252,211,77,0.35)]">
            <div className="flex items-center gap-[0.8vw] text-[1.8vw] font-semibold text-amber-200">
              <span className="relative flex h-[1.2vw] w-[1.2vw]">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber-300 opacity-75" />
                <span className="relative inline-flex h-[1.2vw] w-[1.2vw] rounded-full bg-amber-300" />
              </span>
              진료실 호출
            </div>
            <div className="mt-[3vh] text-[9vw] font-black leading-none tracking-wider text-amber-300 tabular-nums">
              {announcing.visitNo}
            </div>
            {announcing.name && <div className="mt-[4vh] text-[5vw] font-bold">{announcing.name} 님</div>}
            <div className="mt-[2vh] text-[3.2vw]">진료실로 방문해 주세요</div>
          </div>
        </div>
      )}
    </div>
  );
}
