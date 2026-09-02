import { useEffect, useState, type ReactNode } from "react";
import { useParams } from "react-router-dom";
import { getSharedVisitSummary, type SharedVisitSummary as SharedVisitSummaryData } from "../api/documents";
import { getErrorMessage } from "../api/errors";
import { PublicDocumentFrame, PublicDocumentNotice } from "../components/PublicDocumentFrame";
import { formatDateTime } from "../utils/datetime";

/**
 * 감열지 진료요약서의 QR 목적지 (`/d/v/:token`).
 *
 * 종이에 찍힌 항목과 같은 것을 화면으로 다시 보여준다. 감열지는 열·직사광선에
 * 닿으면 수개월 안에 글자가 사라지기 때문에, 종이가 바래도 내용을 확인할 수 있는
 * 자리가 필요하다.
 *
 * 자세한 접근 제어 배경은 {@link ../api/documents} 와 백엔드 DocumentShareService 주석 참고.
 */
export default function SharedVisitSummary() {
  const { token } = useParams<{ token: string }>();

  const [summary, setSummary] = useState<SharedVisitSummaryData | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  // 토큰이 없는 주소는 서버에 물어볼 것이 없으니 로딩 상태로 시작하지도 않는다.
  const [loading, setLoading] = useState(Boolean(token));

  useEffect(() => {
    // 토큰 유무는 주소만 보면 아는 값이라 effect 안에서 state 로 옮겨 담지 않는다.
    // 렌더를 한 번 더 돌리는 데다, 린트(react-hooks/set-state-in-effect)도 막는다.
    if (!token) return;

    // 요청 시작 시점에 loading/error 를 되돌리지 않는 이유: 이 화면은 QR 로 새 탭이
    // 열리는 진입점이고 다른 문서로 넘어가는 링크가 없어서, 한 번 마운트된 뒤
    // token 이 바뀌는 경로가 없다. 아래 두 콜백이 상태를 통째로 확정한다.
    let cancelled = false;

    getSharedVisitSummary(token)
      .then((data) => {
        if (cancelled) return;
        setSummary(data);
        setFetchError(null);
      })
      .catch((err) => {
        // 410(기간 만료)과 404(없는 링크)의 문구가 다르다 — api/errors.ts 참고.
        if (cancelled) return;
        setSummary(null);
        setFetchError(getErrorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [token]);

  const error = token ? fetchError : "링크가 올바르지 않습니다.";

  return (
    <PublicDocumentFrame
      title="진료 요약"
      subtitle="진료 내용을 확인하는 화면입니다."
      expiresAt={summary?.expiresAt}
    >
      {loading && <PublicDocumentNotice>불러오는 중입니다...</PublicDocumentNotice>}

      {!loading && error && (
        <PublicDocumentNotice>
          <p className="font-semibold text-gray-800">진료 내용을 열 수 없습니다</p>
          <p className="mt-2">{error}</p>
        </PublicDocumentNotice>
      )}

      {!loading && !error && summary && (
        <div className="space-y-4 rounded-xl border border-gray-300 bg-white px-5 py-6 text-sm text-gray-800 shadow-sm">
          <section className="space-y-1">
            <Row label="환자" value={`${summary.patientName} (${summary.patientNo})`} />
            <Row label="접수번호" value={summary.visitNo} />
            <Row label="진료일시" value={formatDateTime(summary.visitDateTime)} />
            <Row label="담당의" value={summary.doctorName ?? "-"} />
          </section>

          <Section title="진단">
            {summary.diseases.length > 0 ? (
              <ul className="space-y-1">
                {summary.diseases.map((d) => (
                  <li key={d.code}>
                    <span className="font-mono text-xs text-gray-500">{d.code}</span>{" "}
                    {d.nameKo}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-gray-500">등록된 진단이 없습니다.</p>
            )}
          </Section>

          <Section title="처방">
            {summary.prescriptions.length > 0 ? (
              <ul className="space-y-2">
                {summary.prescriptions.map((m, i) => (
                  <li key={`${m.drugName}-${i}`}>
                    <div>{m.drugName}</div>
                    <div className="text-xs text-gray-500">
                      {[m.dosage, m.durationDays ? `${m.durationDays}일` : null]
                        .filter(Boolean)
                        .join(" / ") || "-"}
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-gray-500">처방 없음</p>
            )}
          </Section>

          {summary.aiSummary?.trim() && (
            <Section title="AI 분석 참고">
              <p className="whitespace-pre-wrap">{summary.aiSummary.trim()}</p>
              {/* 종이와 같은 문구를 화면에도 남긴다. AI 문장이 의사 확인 없이
                  환자에게 전달된 것처럼 보이면 안 된다. */}
              <p className="mt-2 text-xs text-gray-500">※ 의사 확인 완료</p>
            </Section>
          )}
        </div>
      )}
    </PublicDocumentFrame>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-3">
      <span className="w-20 shrink-0 text-gray-500">{label}</span>
      <span className="flex-1">{value}</span>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-t border-gray-200 pt-3">
      <h2 className="mb-2 text-xs font-bold tracking-wide text-gray-500">{title}</h2>
      {children}
    </section>
  );
}
