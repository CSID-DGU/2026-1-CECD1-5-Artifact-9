import { useState, useEffect, useRef, useCallback } from "react";

/**
 * MicroscopeShortcutCapture
 * -------------------------------------------------------------------------
 * DLscope(제조사 전용 앱)는 URL scheme/SDK가 공개되어 있지 않아 웹페이지가
 * 직접 제어할 수 없습니다. 대신 iOS의 "단축어(Shortcuts)" 앱을 다리로 써서
 * 아래 흐름을 만듭니다.
 *
 *   1) 이 화면에서 "사진 촬영" 클릭
 *      → 백엔드에 세션 토큰 발급 요청
 *      → shortcuts://run-shortcut?name=현미경촬영&input=text&text={token}
 *        URL로 이동, iOS 단축어 앱 실행 (그 안에서 DLscope가 열림)
 *   2) 사용자가 DLscope에서 촬영 후, 단축어 앱으로 돌아와 이어서 실행
 *      → 단축어가 방금 찍은 사진을 찾아 백엔드로 업로드
 *      → 업로드 후 단축어가 키오스크 페이지 URL로 다시 열어줌
 *         (예: https://<kiosk>/capture-complete?token={token})
 *   3) 이 컴포넌트는 세션 토큰을 기준으로 백엔드를 폴링하며,
 *      사진이 올라오면 자동으로 미리보기를 표시합니다.
 *
 * ※ 실제 "현미경촬영" 단축어는 Shortcuts 앱에서 직접 구성해야 합니다.
 */

type CaptureStatus = "idle" | "creating" | "waiting" | "found" | "error";

interface CaptureResult {
  blob: Blob;
  url: string;
}

interface CreateSessionResponse {
  sessionToken: string;
}

export interface MicroscopeShortcutCaptureProps {
  /** Spring Boot 백엔드 base URL, 예: http://192.168.0.15:8080 */
  apiBase: string;
  /** Shortcuts 앱에 등록한 단축어 이름 (기본값: "현미경촬영") */
  shortcutName?: string;
  /** 사진 업로드가 감지되었을 때 호출 */
  onCapture?: (result: CaptureResult) => void;
  /** 모달 닫기 */
  onClose?: () => void;
  /** 폴링 주기(ms), 기본 2000 */
  pollIntervalMs?: number;
}

export default function MicroscopeShortcutCapture({
  apiBase,
  shortcutName = "현미경촬영",
  onCapture,
  onClose,
  pollIntervalMs = 2000,
}: MicroscopeShortcutCaptureProps) {
  const [status, setStatus] = useState<CaptureStatus>("idle");
  const [errorMsg, setErrorMsg] = useState<string>("");
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const startPolling = useCallback(
    (token: string) => {
      stopPolling();
      pollRef.current = setInterval(async () => {
        try {
          const res = await fetch(`${apiBase}/api/capture-sessions/${token}/photo`);
          if (res.status === 200) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            setStatus("found");
            stopPolling();
            onCapture?.({ blob, url });
          }
          // 404 등 비-200 응답은 아직 업로드 전이므로 계속 대기
        } catch {
          // 네트워크 일시 오류는 무시하고 다음 폴링에서 재시도
        }
      }, pollIntervalMs);
    },
    [apiBase, onCapture, pollIntervalMs, stopPolling]
  );

  useEffect(() => stopPolling, [stopPolling]);

  const handleStartCapture = async () => {
    setStatus("creating");
    setErrorMsg("");
    try {
      const res = await fetch(`${apiBase}/api/capture-sessions`, { method: "POST" });
      if (!res.ok) throw new Error("세션 생성 실패");
      const { sessionToken }: CreateSessionResponse = await res.json();

      setStatus("waiting");
      startPolling(sessionToken);

      // iOS 단축어 앱 실행 (그 안에서 DLscope가 열리도록 구성)
      const url = `shortcuts://run-shortcut?name=${encodeURIComponent(
        shortcutName
      )}&input=text&text=${encodeURIComponent(sessionToken)}`;
      window.location.href = url;
    } catch (err) {
      console.error(err);
      setStatus("error");
      setErrorMsg("촬영 세션을 시작하지 못했습니다. 네트워크 연결을 확인해주세요.");
    }
  };

  const handleClose = () => {
    stopPolling();
    onClose?.();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="w-full max-w-md rounded-xl bg-white shadow-xl overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
          <h2 className="text-base font-medium text-gray-900">현미경 촬영</h2>
          <button onClick={handleClose} className="text-gray-400 hover:text-gray-600 text-xl leading-none" aria-label="닫기">
            ✕
          </button>
        </div>

        <div className="px-6 py-10 text-center">
          {status === "idle" && (
            <>
              <div className="mx-auto mb-3 text-4xl" aria-hidden="true">📷</div>
              <p className="mb-1 text-sm font-medium text-gray-900">현미경으로 촬영할 준비가 되었나요?</p>
              <p className="mb-5 text-sm text-gray-500">
                촬영 버튼을 누르면 단축어 앱이 열리고, DLscope로 이동합니다.
                촬영 후 단축어 화면으로 돌아와 계속 진행해주세요.
              </p>
              <button
                onClick={handleStartCapture}
                className="rounded-lg bg-teal-600 px-5 py-2.5 text-sm text-white hover:bg-teal-700"
              >
                사진 촬영 시작
              </button>
            </>
          )}

          {status === "creating" && <p className="text-sm text-gray-500">촬영 세션을 준비하는 중...</p>}

          {status === "waiting" && (
            <>
              <div
                className="mx-auto mb-3 h-7 w-7 animate-spin rounded-full border-2 border-teal-500 border-t-transparent"
                aria-hidden="true"
              />
              <p className="mb-1 text-sm font-medium text-gray-900">촬영한 사진을 기다리는 중입니다</p>
              <p className="text-sm text-gray-500">
                DLscope에서 촬영 후, 단축어 앱으로 돌아와 이어서 실행해주세요.
                완료되면 이 화면이 자동으로 넘어갑니다.
              </p>
            </>
          )}

          {status === "found" && (
            <>
              <div className="mx-auto mb-3 text-3xl" aria-hidden="true">✅</div>
              <p className="text-sm font-medium text-gray-900">사진을 불러왔습니다</p>
            </>
          )}

          {status === "error" && (
            <>
              <div className="mx-auto mb-3 text-3xl" aria-hidden="true">⚠️</div>
              <p className="mb-4 text-sm text-gray-700">{errorMsg}</p>
              <button
                onClick={handleStartCapture}
                className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
              >
                다시 시도
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
