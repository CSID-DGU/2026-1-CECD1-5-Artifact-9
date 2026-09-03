import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import {
  getSharedCertificateGate,
  verifySharedCertificate,
  type SharedCertificate as SharedCertificateData,
  type SharedDocumentGate,
} from "../api/documents";
import { getErrorMessage } from "../api/errors";
import { CertificateDocumentView } from "../components/CertificateDocumentView";
import { PublicDocumentFrame, PublicDocumentNotice } from "../components/PublicDocumentFrame";

/** 생년월일 자리수. `19900101` 처럼 연도 네 자리까지 받는다. */
const BIRTH_DATE_LENGTH = 8;

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
 * <b>토큰만으로는 열리지 않는다.</b> 화면은 두 단계다 —
 *   1. 링크가 살아 있는지 확인(GET). 이 응답에는 증명서 내용이 없다.
 *   2. 환자 생년월일을 맞추면(POST verify) 그제서야 내용이 온다.
 * 이 순서가 중요하다. 내용을 미리 받아두고 화면에서만 가리면, 종이를 주운 사람이
 * 개발자도구 Network 탭에서 진단명과 소견을 그대로 읽을 수 있다. 잠금이 아니라
 * 가림막일 뿐이다. 그래서 판정은 전부 서버에서 하고, 이 화면은 결과만 그린다.
 *
 * 보여주는 내용은 발급 당시 스냅샷 그대로이며, 발급 화면과 같은
 * {@link CertificateDocumentView} 로 그린다 — 종이와 화면이 어긋나지 않게 하려는 것이다.
 */
export default function SharedCertificate() {
  const { token } = useParams<{ token: string }>();
  const birthDateInputRef = useRef<HTMLInputElement | null>(null);

  const [gate, setGate] = useState<SharedDocumentGate | null>(null);
  const [gateError, setGateError] = useState<string | null>(null);
  // 토큰이 없는 주소는 서버에 물어볼 것이 없으니 로딩 상태로 시작하지도 않는다.
  const [loading, setLoading] = useState(Boolean(token));

  const [birthDate, setBirthDate] = useState("");
  const [verifying, setVerifying] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [certificate, setCertificate] = useState<SharedCertificateData | null>(null);

  useEffect(() => {
    // 토큰 유무는 주소만 보면 아는 값이라 effect 안에서 state 로 옮겨 담지 않는다.
    // 렌더를 한 번 더 돌리는 데다, 린트(react-hooks/set-state-in-effect)도 막는다.
    if (!token) return;

    // 요청 시작 시점에 loading/error 를 되돌리지 않는 이유: 이 화면은 QR 로 새 탭이
    // 열리는 진입점이고 다른 문서로 넘어가는 링크가 없어서, 한 번 마운트된 뒤
    // token 이 바뀌는 경로가 없다. 아래 두 콜백이 상태를 통째로 확정한다.
    let cancelled = false;

    getSharedCertificateGate(token)
      .then((data) => {
        if (cancelled) return;
        setGate(data);
        setGateError(null);
      })
      .catch((err) => {
        // 410(기간 만료)과 404(없는 링크)의 문구가 다르다 — api/errors.ts 참고.
        if (cancelled) return;
        setGate(null);
        setGateError(getErrorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [token]);

  const handleVerify = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token || birthDate.length !== BIRTH_DATE_LENGTH || verifying) return;

    setVerifying(true);
    setVerifyError(null);
    try {
      setCertificate(await verifySharedCertificate(token, birthDate));
    } catch (err) {
      // 틀린 횟수·잠금 시간은 서버가 문구에 담아 보낸다. 화면에서 다시 세지 않는 이유는,
      // 실제 판정이 서버에 있는데 화면이 따로 세면 두 숫자가 어긋나기 때문이다.
      setVerifyError(getErrorMessage(err));
      setBirthDate("");
      birthDateInputRef.current?.focus();
    } finally {
      setVerifying(false);
    }
  };

  const error = token ? gateError : "링크가 올바르지 않습니다.";

  return (
    <PublicDocumentFrame
      title="증명서 열람"
      subtitle={
        certificate
          ? "병원에서 발급한 서류의 내용을 확인하는 화면입니다."
          : "본인 확인 후 서류 내용을 확인할 수 있습니다."
      }
      expiresAt={certificate?.expiresAt ?? gate?.expiresAt}
    >
      {loading && <PublicDocumentNotice>불러오는 중입니다...</PublicDocumentNotice>}

      {!loading && error && (
        <PublicDocumentNotice>
          <p className="font-semibold text-gray-800">서류를 열 수 없습니다</p>
          <p className="mt-2">{error}</p>
        </PublicDocumentNotice>
      )}

      {/* 본인 확인 단계. 링크는 살아 있지만 아직 내용을 받지 않은 상태다. */}
      {!loading && !error && !certificate && (
        <div className="rounded-xl border border-gray-300 bg-white px-6 py-10">
          <div className="mx-auto w-full max-w-xs text-center">
            <p className="text-sm font-semibold text-gray-800">본인 확인</p>
            <p className="mt-2 text-xs leading-relaxed text-gray-500">
              서류에 기재된 환자 본인의 생년월일을
              <br />
              숫자 8자리로 입력해 주세요.
            </p>

            <form onSubmit={handleVerify} className="mt-5 flex flex-col gap-2">
              <input
                ref={birthDateInputRef}
                value={birthDate}
                // 숫자만 남긴다. 하이픈을 넣어 치는 사람도 있어서, 막기보다 걸러 담는다.
                onChange={(e) =>
                  setBirthDate(e.target.value.replace(/\D/g, "").slice(0, BIRTH_DATE_LENGTH))
                }
                inputMode="numeric"
                autoComplete="off"
                autoFocus
                placeholder="예: 19900101"
                aria-label="생년월일 8자리"
                className="w-full rounded border border-gray-300 px-3 py-2 text-center text-base tracking-[0.2em] text-gray-800 outline-none placeholder:tracking-normal placeholder:text-gray-400 focus:border-blue-500"
              />
              <button
                type="submit"
                disabled={birthDate.length !== BIRTH_DATE_LENGTH || verifying}
                className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-gray-300"
              >
                {verifying ? "확인 중..." : "확인"}
              </button>
            </form>

            {verifyError && (
              <p className="mt-3 rounded border border-red-300 bg-red-50 px-3 py-2 text-[11px] leading-relaxed text-red-700">
                {verifyError}
              </p>
            )}

            <p className="mt-5 text-[11px] leading-relaxed text-gray-400">
              생년월일이 확인되기 전까지는 서류 내용이 표시되지 않습니다.
            </p>
          </div>
        </div>
      )}

      {!loading && !error && certificate && (
        <div className="rounded-xl border border-gray-300 bg-white shadow-sm">
          <CertificateDocumentView certificate={certificate} />
        </div>
      )}
    </PublicDocumentFrame>
  );
}
