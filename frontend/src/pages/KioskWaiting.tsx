import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { getKioskPending } from "../api/kiosk";
import { Card } from "../components/Card";

/** 디코딩용 축소 한계. 태블릿 사진은 4000px급이라 원본 그대로 돌리면 느리다. */
const MAX_DECODE_DIMENSION = 1600;
const SCANNER_BUFFER_RESET_MS = 500;
/** 대기 상태 안내 문구. 화면 진입 직후부터 이 상태로 시작한다(별도 시작 버튼 없음). */
const SCANNER_IDLE_MESSAGE = "QR 리더기 입력 대기 중입니다. 접수 QR을 스캔해 주세요.";

/** 파일 → 디코딩 가능한 이미지. onload 이후엔 픽셀이 메모리에 있으므로 objectURL을 바로 회수해도 된다. */
function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("이미지를 읽지 못했습니다."));
    };
    img.src = url;
  });
}

type QrDecoder = typeof import("jsqr").default;

/** 캔버스에 그려 픽셀을 뽑고 QR을 읽는다. 못 읽으면 null. */
function readQr(jsQR: QrDecoder, img: HTMLImageElement, maxDimension: number): string | null {
  const scale = Math.min(1, maxDimension / Math.max(img.naturalWidth, img.naturalHeight));
  const width = Math.max(1, Math.round(img.naturalWidth * scale));
  const height = Math.max(1, Math.round(img.naturalHeight * scale));

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;

  ctx.drawImage(img, 0, 0, width, height);
  const { data } = ctx.getImageData(0, 0, width, height);
  return jsQR(data, width, height, { inversionAttempts: "attemptBoth" })?.data ?? null;
}

/**
 * 스캔 결과에서 토큰만 뽑는다.
 * QR에 적힌 origin(접수 화면에서 설정한 주소)과 태블릿이 실제 접속한 origin이 다를 수 있어서,
 * 전체 URL로 이동하지 않고 토큰만 취해 현재 origin에서 라우팅한다.
 */
function extractToken(scanned: string): string | null {
  const normalized = scanned.trim();
  const fromPath = normalized.match(/\/kiosk\/([A-Za-z0-9]{12})(?:[/?#]|$)/);
  if (fromPath) return fromPath[1];

  return /^[A-Za-z0-9]{12}$/.test(normalized) ? normalized : null;
}

/**
 * 대기실 태블릿이 상시 띄워두는 화면. 로그인 없이 접근 가능(라우팅에서 인증 가드 제외).
 *
 * 진입 경로 세 가지:
 *   1. USB QR 리더기 — 화면에 들어온 순간부터 항상 대기 상태이고, 키보드 입력처럼 받아 토큰 화면으로 이동.
 *   2. 태블릿 기본 카메라/렌즈로 QR을 찍어 /kiosk/{token} 으로 직접 진입.
 *   3. ?auto=1 — 3초 폴링으로 대기 환자를 잡아 자동 이동(QR 없이 시연할 때의 폴백).
 * 어느 쪽이든 목적지가 /kiosk/{token} 이라 이후 흐름은 완전히 동일하다.
 */
export default function KioskWaiting() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const autoMode = searchParams.get("auto") === "1";

  const cameraInputRef = useRef<HTMLInputElement | null>(null);
  const scannerInputRef = useRef<HTMLInputElement | null>(null);
  const scannerBufferRef = useRef("");
  const scannerResetTimerRef = useRef<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [scannerStatus, setScannerStatus] = useState(SCANNER_IDLE_MESSAGE);

  useEffect(() => {
    if (!autoMode) return;

    let cancelled = false;
    let inFlight = false;

    const interval = setInterval(async () => {
      if (inFlight) return;
      inFlight = true;
      try {
        const pending = await getKioskPending();
        if (!cancelled) navigate(`/kiosk/${pending.kioskToken}`);
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
  }, [autoMode, navigate]);

  useEffect(() => {
    const resetScannerBuffer = () => {
      scannerBufferRef.current = "";
      if (scannerResetTimerRef.current) {
        window.clearTimeout(scannerResetTimerRef.current);
        scannerResetTimerRef.current = null;
      }
    };

    const submitScannedText = (text: string) => {
      const token = extractToken(text);
      resetScannerBuffer();

      if (!token) {
        setScanError("키오스크 QR이 아닙니다. 접수 화면에 표시된 QR을 다시 스캔해 주세요.");
        setScannerStatus("인식 실패 · 접수 QR을 다시 스캔해 주세요.");
        window.setTimeout(() => scannerInputRef.current?.focus(), 0);
        return;
      }

      setScanError(null);
      setScannerStatus("QR 인식 완료 · 분석 화면으로 이동합니다.");
      navigate(`/kiosk/${token}`);
    };

    const handleScannerKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key === "Shift" || event.key === "CapsLock" || event.key === "Tab") return;

      if (event.key === "Enter") {
        const scanned = scannerBufferRef.current.trim();
        if (scanned) {
          event.preventDefault();
          submitScannedText(scanned);
        }
        return;
      }

      if (event.key.length !== 1) return;

      scannerBufferRef.current += event.key;
      setScannerStatus("QR 입력 감지 중...");

      const token = extractToken(scannerBufferRef.current);
      if (token) {
        submitScannedText(scannerBufferRef.current);
        return;
      }

      if (scannerResetTimerRef.current) {
        window.clearTimeout(scannerResetTimerRef.current);
      }
      scannerResetTimerRef.current = window.setTimeout(() => {
        resetScannerBuffer();
        setScannerStatus(SCANNER_IDLE_MESSAGE);
      }, SCANNER_BUFFER_RESET_MS);
    };

    window.addEventListener("keydown", handleScannerKeyDown);
    return () => {
      window.removeEventListener("keydown", handleScannerKeyDown);
      resetScannerBuffer();
    };
  }, [navigate]);

  /**
   * 화면에 들어오면 바로 숨은 입력창에 포커스를 준다.
   * keydown은 window에 걸려 있어 포커스와 무관하게 잡히지만, 포커스가 버튼에 있으면
   * 리더기가 마지막에 보내는 Enter가 그 버튼을 눌러버린다. 포커스를 여기 묶어 그걸 막는다.
   */
  useEffect(() => {
    if (autoMode) return;
    scannerInputRef.current?.focus();
  }, [autoMode]);

  const openCamera = () => {
    if (!cameraInputRef.current) return;
    cameraInputRef.current.value = ""; // 같은 사진을 다시 찍어도 change가 발생하도록
    cameraInputRef.current.click();
  };

  const handleQrPhoto = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setScanError(null);
    setScanning(true);
    try {
      // 디코더는 촬영 버튼을 눌렀을 때만 받는다 (약 50KB, 나머지 화면엔 불필요)
      const [{ default: jsQR }, img] = await Promise.all([import("jsqr"), loadImage(file)]);
      // 축소본으로 먼저 시도하고, 실패하면 원본 해상도로 한 번 더 (QR이 작게 찍힌 경우)
      const scanned =
        readQr(jsQR, img, MAX_DECODE_DIMENSION) ??
        readQr(jsQR, img, Math.max(img.naturalWidth, img.naturalHeight));
      const token = scanned ? extractToken(scanned) : null;

      if (!token) {
        setScanError(
          scanned
            ? "키오스크 QR이 아닙니다. 접수 화면에 표시된 QR을 찍어 주세요."
            : "QR을 인식하지 못했습니다. 화면 반사를 피하고 QR이 크게 나오도록 다시 찍어 주세요.",
        );
        return;
      }
      navigate(`/kiosk/${token}`);
    } catch {
      setScanError("사진을 읽지 못했습니다. 다시 시도해 주세요.");
    } finally {
      setScanning(false);
    }
  };

  return (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col">
      <header className="h-10 bg-blue-500 flex items-center px-4 shrink-0">
        <span className="text-blue-200 text-xs">AI 보조 진단 시스템 · 대기실 키오스크</span>
      </header>

      <main className="flex flex-1 items-center justify-center p-6">
        <Card title="대기 화면" className="w-full max-w-md" contentClassName="!p-6">
          <div className="flex flex-col items-center gap-3 py-2 text-center">
            <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-600 border-t-blue-500" />

            {autoMode ? (
              <div>
                <p className="text-base font-semibold text-white">환자를 기다리는 중입니다</p>
                <p className="mt-1 text-xs text-gray-400">접수가 완료되면 자동으로 분석 화면으로 이동합니다.</p>
                <p className="mt-1 text-[11px] text-gray-500">자동 진입 모드 (QR 스캔 없이 동작)</p>
              </div>
            ) : (
              <p className="text-xs leading-relaxed text-gray-400">
                접수처 화면의 QR 코드를 스캔하면
                <br />
                본인 확인 후 피부 사진 예비 분석을 진행합니다.
              </p>
            )}

            {!autoMode && (
              <p className="w-full rounded border border-blue-500/30 bg-blue-500/10 px-3 py-2 text-[11px] leading-relaxed text-blue-100">
                {scannerStatus}
              </p>
            )}

            {/* QR 리더기는 HID 키보드처럼 입력되므로 포커스 받을 입력창을 열어둔다(진입 즉시 포커스). */}
            <input
              ref={scannerInputRef}
              type="text"
              inputMode="none"
              autoComplete="off"
              aria-label="QR 리더기 입력"
              className="h-px w-px opacity-0"
              onFocus={() => {
                if (!autoMode) setScannerStatus(SCANNER_IDLE_MESSAGE);
              }}
              onBlur={() => {
                if (!autoMode) window.setTimeout(() => scannerInputRef.current?.focus(), 0);
              }}
            />

            {/* 앱 안에서 직접 QR을 디코딩한다 — QR 리더기 장애 시 사용할 예비 동선 */}
            <input
              ref={cameraInputRef}
              type="file"
              accept="image/*"
              capture="environment"
              onChange={handleQrPhoto}
              className="hidden"
            />
            <button
              type="button"
              onClick={openCamera}
              disabled={scanning}
              className="mt-1 w-full rounded border border-blue-400/40 bg-blue-500/10 px-3 py-2 text-xs font-semibold text-blue-100 transition hover:bg-blue-500/20 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {scanning ? "QR 인식 중..." : "카메라로 QR 촬영"}
            </button>

            {scanError && (
              <p className="w-full rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-[11px] leading-relaxed text-red-200">
                {scanError}
              </p>
            )}
          </div>
        </Card>
      </main>
    </div>
  );
}
