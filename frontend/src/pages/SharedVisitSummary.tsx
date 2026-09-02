import { useEffect, useState, type ReactNode } from "react";
import { useParams } from "react-router-dom";
import { getSharedVisitSummary, type SharedVisitSummary as SharedVisitSummaryData } from "../api/documents";
import { getErrorMessage } from "../api/errors";
import {
  PublicDocumentFrame,
  PublicDocumentNotice,
  formatDateTime,
} from "../components/PublicDocumentFrame";

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
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!token) {
      setError("링크가 올바르지 않습니다.");
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    getSharedVisitSummary(token)
      .then((data) => {
        if (!cancelled) setSummary(data);
      })
      .catch((err) => {
        if (!cancelled) setError(getErrorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [token]);

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
