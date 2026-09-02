import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { getSharedCertificate, type SharedCertificate as SharedCertificateData } from "../api/documents";
import { getErrorMessage } from "../api/errors";
import { CertificateDocumentView } from "../components/CertificateDocumentView";
import { PublicDocumentFrame, PublicDocumentNotice } from "../components/PublicDocumentFrame";

/**
 * 감열지 발급확인증의 QR 목적지 (`/d/c/:token`).
 *
 * 환자가 종이를 받자마자 찍는 화면이라 로그인이 없다. 예전 QR 은
 * `/main/certificate?serialNo=...` 를 가리켰는데, 그건 직원용 화면이라
 * 환자에게는 로그인 창만 보였다. 게다가 발급번호는 순번이라 앞뒤 번호로
 * 남의 증명서를 열어볼 수 있었다.
 *
 * 지금은 주소에 base62 12자 토큰만 실리고, 그 토큰은 이 증명서 한 건만 연다.
 * 다른 문서로 이동할 수 있는 링크를 이 화면에 두지 않는 이유도 같다.
 *
 * 보여주는 내용은 발급 당시 스냅샷 그대로이며, 발급 화면과 같은
 * {@link CertificateDocumentView} 로 그린다 — 종이와 화면이 어긋나지 않게 하려는 것이다.
 */
export default function SharedCertificate() {
  const { token } = useParams<{ token: string }>();

  const [certificate, setCertificate] = useState<SharedCertificateData | null>(null);
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

    getSharedCertificate(token)
      .then((data) => {
        if (!cancelled) setCertificate(data);
      })
      .catch((err) => {
        // 410(기간 만료)과 404(없는 링크)의 문구가 다르다 — api/errors.ts 참고.
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
      title="증명서 열람"
      subtitle="병원에서 발급한 서류의 내용을 확인하는 화면입니다."
      expiresAt={certificate?.expiresAt}
    >
      {loading && <PublicDocumentNotice>불러오는 중입니다...</PublicDocumentNotice>}

      {!loading && error && (
        <PublicDocumentNotice>
          <p className="font-semibold text-gray-800">서류를 열 수 없습니다</p>
          <p className="mt-2">{error}</p>
        </PublicDocumentNotice>
      )}

      {!loading && !error && certificate && (
        <div className="rounded-xl border border-gray-300 bg-white shadow-sm">
          <CertificateDocumentView certificate={certificate} />
        </div>
      )}
    </PublicDocumentFrame>
  );
}
