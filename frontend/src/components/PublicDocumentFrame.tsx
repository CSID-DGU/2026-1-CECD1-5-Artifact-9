import type { ReactNode } from "react";

/**
 * 감열지 QR 로 들어온 환자가 보는 화면의 공통 틀.
 *
 * 병원 직원용 화면(MainLayout)과 일부러 분리했다. 이쪽에는 로그인도, 사이드바도,
 * 다른 환자로 넘어갈 수 있는 링크도 없다 — 이 페이지에서 갈 수 있는 곳은 없어야 한다.
 *
 * 인쇄 버튼도 두지 않는다. 효력 있는 증명서는 병원에서 A4 로 발급한 것뿐이고,
 * 이 화면은 그 내용을 확인시켜 주는 용도다.
 */
export function PublicDocumentFrame({
  title,
  subtitle,
  expiresAt,
  children,
}: {
  title: string;
  subtitle: string;
  /** 링크 만료 시각. 아직 못 받았으면(로딩·오류) 생략한다. */
  expiresAt?: string | null;
  children: ReactNode;
}) {
  return (
    <div className="min-h-screen bg-gray-100 py-6 px-3">
      <div className="mx-auto w-full max-w-[210mm]">
        <header className="mb-4 text-center">
          <h1 className="text-lg font-bold text-gray-800">{title}</h1>
          <p className="mt-1 text-xs text-gray-500">{subtitle}</p>
        </header>

        {children}

        <footer className="mt-4 space-y-1 text-center text-[11px] leading-relaxed text-gray-500">
          {expiresAt && <p>이 링크는 {formatDateTime(expiresAt)} 까지 열람할 수 있습니다.</p>}
          <p>본 화면은 열람용이며, 법적 효력이 있는 서류는 병원에서 발급한 A4 원본입니다.</p>
        </footer>
      </div>
    </div>
  );
}

/** 로딩·오류를 같은 자리에 같은 모양으로 띄운다. */
export function PublicDocumentNotice({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-xl border border-gray-300 bg-white px-6 py-10 text-center text-sm text-gray-600">
      {children}
    </div>
  );
}

export function formatDateTime(raw: string | null): string {
  if (!raw) return "-";
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return raw;
  return d.toLocaleString("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
