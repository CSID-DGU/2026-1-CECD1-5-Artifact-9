import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { getErrorMessage, isNotFound } from "../api/errors";
import { analyzeKioskSession, getKioskSession, type KioskSession, type PreliminaryAnalysis } from "../api/kiosk";
import { Card } from "../components/Card";
import { Button } from "../components/Button";
import { formatConfidence } from "../utils/confidence";

const DISCLAIMER =
  "⚠️ 본 분석은 AI 보조 참고용이며 의학적 진단이 아닙니다. 결과가 정확하지 않을 수 있으며, 반드시 진료실에서 의사의 확인 진료와 처방을 받으셔야 합니다.";

/**
 * 결과 화면 상단용 축약 고지.
 *
 * <p>전체 문구를 위아래로 두 번 쓰면 같은 글이 반복돼 읽히지 않는다. 그렇다고 위쪽을 없애면,
 * 환자가 스크롤하기 전에 보는 것은 "악성 흑색종 99.2%" 같은 숫자뿐이다.
 * 그래서 위에는 숫자 옆에 붙는 한 줄, 아래 확인 버튼 앞에는 전체 문구를 둔다.
 */
const SHORT_DISCLAIMER = "⚠️ AI 보조 참고용 결과입니다 · 의학적 진단이 아닙니다";

const MIN_ANALYSIS_LOADING_MS = 1200;

/**
 * QR로 진입한 환자 1명의 예비분석 화면. 경로의 토큰이 곧 대상 접수를 가리킨다.
 * 흐름: 세션 조회 → 본인 확인 → 사진 선택/촬영 → 분석 → 결과.
 */
export default function KioskAnalyze() {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const galleryInputRef = useRef<HTMLInputElement | null>(null);
  const cameraInputRef = useRef<HTMLInputElement | null>(null);

  const [session, setSession] = useState<KioskSession | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);

  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [gradcamLoading, setGradcamLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [gradcamError, setGradcamError] = useState(false);
  const [result, setResult] = useState<PreliminaryAnalysis | null>(null);
  const [imageViewMode, setImageViewMode] = useState<"heatmap" | "original">("heatmap");

  // 촬영한 원본 사진의 blob URL. 결과 화면의 "원본 이미지" 토글이 이 값을 그대로 쓴다 —
  // 방금 올린 파일이라 서버에 다시 요청할 필요가 없고, 태블릿에는 JWT도 없다.
  // 교체·이탈 시 revoke 하지 않으면 재촬영을 반복하는 만큼 메모리에 쌓인다.
  const previewUrlRef = useRef<string | null>(null);

  useEffect(() => () => {
    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
  }, []);

  // 잘못된 QR을 찍었거나 토큰이 만료된 경우를 촬영 전에 걸러낸다.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;

    getKioskSession(token)
      .then((data) => {
        if (!cancelled) setSession(data);
      })
      .catch((error) => {
        if (cancelled) return;
        // 404만 "QR이 잘못됐다"는 뜻이다. 서버가 죽었거나 태블릿 와이파이가 끊긴 경우까지
        // 같은 문구를 띄우면, 환자는 멀쩡한 QR을 들고 접수처로 되돌아가게 된다.
        setSessionError(
          isNotFound(error)
            ? "유효하지 않은 QR 코드입니다. 접수처에 문의해 주세요."
            : `${getErrorMessage(error)} 문제가 계속되면 접수처에 문의해 주세요.`
        );
      });

    return () => {
      cancelled = true;
    };
  }, [token]);

  // 태블릿에는 JWT가 없다 — 히트맵도 QR 토큰 경로로만 받는다(서버가 gradcamUrl에 그 경로를 내려준다).
  // 폴백 URL도 visitId가 아니라 토큰으로 조립해야 한다. visitId는 순차 정수라 남의 접수에 닿는다.
  const gradcamImageUrl = result
    ? `${result.gradcamUrl ?? `/api/kiosk/session/${encodeURIComponent(token ?? "")}/heatmap`}?t=${encodeURIComponent(result.analyzedAt)}`
    : null;

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0] ?? null;
    setFile(selected);
    setResult(null);
    setError(null);
    setGradcamError(false);
    setGradcamLoading(false);
    setImageViewMode("heatmap");

    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    previewUrlRef.current = selected ? URL.createObjectURL(selected) : null;
    setPreviewUrl(previewUrlRef.current);
  };

  const openGalleryPicker = () => {
    if (galleryInputRef.current) {
      galleryInputRef.current.value = "";
      galleryInputRef.current.click();
    }
  };

  const openCameraPicker = () => {
    if (cameraInputRef.current) {
      cameraInputRef.current.value = "";
      cameraInputRef.current.click();
    }
  };

  const handleAnalyze = async () => {
    if (!file || !token) return;
    setLoading(true);
    setGradcamLoading(true);
    setGradcamError(false);
    setError(null);
    try {
      const [response] = await Promise.all([
        analyzeKioskSession(token, file),
        new Promise((resolve) => window.setTimeout(resolve, MIN_ANALYSIS_LOADING_MS)),
      ]);
      setResult(response);
    } catch (err) {
      setError(getErrorMessage(err));
      setGradcamLoading(false);
    } finally {
      setLoading(false);
    }
  };

  const handleConfirm = () => {
    navigate("/kiosk");
  };

  const renderLoadingOverlay = () => (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/70 px-4">
      <div className="w-full max-w-sm rounded-lg border border-blue-400/40 bg-gray-900 p-5 shadow-2xl">
        <div className="flex flex-col items-center gap-4 text-center">
          <div className="relative h-14 w-14">
            <div className="absolute inset-0 rounded-full border-4 border-blue-300/20" />
            <div className="absolute inset-0 animate-spin rounded-full border-4 border-transparent border-t-blue-300 border-r-blue-300" />
            <div className="absolute inset-3 rounded-full bg-blue-500/20" />
          </div>
          <div>
            <p className="text-base font-semibold text-white">AI 분석 및 Grad-CAM 생성중...</p>
            <p className="mt-2 text-xs leading-relaxed text-gray-300">
              병변 후보를 계산하고, 모델이 주목한 영역을 시각화하고 있습니다.
            </p>
          </div>
        </div>
      </div>
    </div>
  );

  /** 헤더 + 중앙 정렬 레이아웃 — 확인/오류/분석 화면이 공유한다. */
  const renderShell = (children: React.ReactNode) => (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col">
      {loading && renderLoadingOverlay()}

      <header className="h-10 bg-blue-500 flex items-center px-4 shrink-0">
        <span className="text-blue-200 text-xs">AI 보조 진단 시스템 · 대기실 키오스크</span>
      </header>

      <main className="flex flex-1 justify-center p-4 overflow-y-auto">
        <div className="flex w-full max-w-md flex-col gap-2">{children}</div>
      </main>
    </div>
  );

  if (sessionError) {
    return renderShell(
      <Card title="확인 필요" contentClassName="!p-6">
        <div className="flex flex-col items-center gap-4 py-4 text-center">
          <div className="text-3xl">⚠️</div>
          <p className="text-sm font-semibold text-red-300">{sessionError}</p>
          <Button onClick={() => navigate("/kiosk")} className="w-full">
            처음으로
          </Button>
        </div>
      </Card>,
    );
  }

  if (!session) {
    return renderShell(
      <Card title="대기 화면" contentClassName="!p-6">
        <div className="flex flex-col items-center gap-3 py-6 text-center">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-600 border-t-blue-500" />
          <p className="text-xs text-gray-400">접수 정보를 확인하는 중입니다...</p>
        </div>
      </Card>,
    );
  }

  if (!confirmed) {
    return renderShell(
      <Card title="본인 확인" contentClassName="!p-6">
        <div className="flex flex-col items-center gap-4 py-2 text-center">
          <div>
            <p className="text-xl font-bold text-white">{session.patientName} 님</p>
            <p className="mt-1 text-xs text-gray-400">접수번호 {session.receptionNumber}</p>
          </div>

          <p className="text-sm text-gray-200">본인이 맞으십니까?</p>

          {session.analyzed && (
            <p className="w-full rounded border border-yellow-500/40 bg-yellow-500/10 px-3 py-2 text-[11px] text-yellow-200">
              이미 예비 분석을 마친 접수입니다. 계속 진행하면 새로 촬영한 사진으로 결과를 다시 만듭니다.
            </p>
          )}

          <div className="flex w-full flex-col gap-2">
            <Button onClick={() => setConfirmed(true)} className="w-full">
              예, 시작하기
            </Button>
            <button
              type="button"
              onClick={() => navigate("/kiosk")}
              className="w-full rounded border border-gray-400 px-3 py-2 text-sm font-medium text-gray-200 transition hover:bg-gray-700 cursor-pointer"
            >
              아니오
            </button>
          </div>
        </div>
      </Card>,
    );
  }

  return renderShell(
    <>
      <p className="rounded border border-blue-500/30 bg-blue-500/10 px-3 py-2 text-[11px] text-blue-100">
        {session.patientName} 님 · 접수번호 {session.receptionNumber}
      </p>
      <Card title="피부 사진 예비 분석" contentClassName="!p-3">
        <div className="flex flex-col gap-3">
          {/* 촬영 전에는 전체 문구를 띄운다. 결과 화면에서는 아래쪽 확인 버튼 앞에 같은 문구가
              다시 나오므로 여기서는 감춘다 — 한 화면에 똑같은 글이 두 번 있으면 둘 다 안 읽힌다. */}
          {!result && (
            <p className="rounded border border-yellow-500/40 bg-yellow-500/10 px-2 py-1.5 text-[11px] text-yellow-200">
              {DISCLAIMER}
            </p>
          )}

          {!result && (
            <div className="flex flex-col items-center gap-3">
              <input
                ref={galleryInputRef}
                type="file"
                accept="image/*"
                onChange={handleFileChange}
                className="hidden"
              />
              <input
                ref={cameraInputRef}
                type="file"
                accept="image/*"
                capture="environment"
                onChange={handleFileChange}
                className="hidden"
              />
              <div className="grid w-full grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={openGalleryPicker}
                  disabled={loading}
                  className="rounded bg-blue-500 px-3 py-3 text-sm font-semibold text-white transition hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  파일 선택
                </button>
                <button
                  type="button"
                  onClick={openCameraPicker}
                  disabled={loading}
                  className="rounded bg-blue-500 px-3 py-3 text-sm font-semibold text-white transition hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  사진 촬영
                </button>
              </div>
              <p className="w-full rounded border border-gray-700 bg-gray-900 px-3 py-2 text-center text-[11px] text-gray-300">
                {file ? file.name : "갤러리에서 선택하거나 태블릿 카메라로 촬영해 주세요."}
              </p>
              <p className="text-center text-[11px] text-gray-400">
                사진 촬영은 태블릿의 후면 카메라를 열고, 파일 선택은 사진 보관함에서 이미지를 선택합니다.
              </p>
              {previewUrl && (
                <div className="w-full rounded border border-gray-700 bg-gray-900 overflow-hidden">
                  <img src={previewUrl} alt="선택한 사진 미리보기" className="max-h-64 w-full object-contain" />
                </div>
              )}
              <Button
                onClick={handleAnalyze}
                disabled={!file || loading}
                className="w-full"
              >
                {loading ? "분석 및 Grad-CAM 생성 중..." : "분석하기"}
              </Button>
              {loading && (
                <div className="flex w-full items-center gap-2 rounded border border-blue-500/30 bg-blue-500/10 px-3 py-2 text-[11px] text-blue-100">
                  <div className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-blue-200/30 border-t-blue-200" />
                  <span>AI가 후보 질환을 분석하고 Grad-CAM 이미지를 생성하는 중입니다.</span>
                </div>
              )}
              {error && <p className="text-xs text-red-400">{error}</p>}
            </div>
          )}

          {result && (
            <div className="flex flex-col gap-2">
              {result.topK[0] && (
                <div className="rounded border border-blue-500/30 bg-blue-500/10 p-2">
                  <p className="text-xs text-gray-300">Top 1</p>
                  <p className="mt-0.5 text-sm font-semibold text-white">
                    {result.topK[0].diseaseNameKo} ({result.topK[0].diseaseCode})
                  </p>
                  <p className="mt-0.5 text-xs text-blue-100">
                    신뢰도 {formatConfidence(result.topK[0].confidence)}
                  </p>
                </div>
              )}

              <p className="text-[11px] leading-relaxed text-yellow-200/90">{SHORT_DISCLAIMER}</p>

              {/* 원본 사진 / 히트맵 토글 — 진료실 화면과 같은 방식이다.
                  <img> 태그를 하나만 두고 src만 바꾼다: 태그가 유지되므로 전환할 때 컨테이너
                  높이가 튀지 않는다. 히트맵만 보여주면 환자는 빨간 얼룩이 자기 피부의 어디인지
                  알 수 없어서, 원본과 번갈아 봐야 비로소 위치가 읽힌다. */}
              {(() => {
                const showHeatmap = imageViewMode === "heatmap" && !gradcamError;
                const displayUrl = showHeatmap ? gradcamImageUrl : previewUrl;

                return (
                  <div className="flex flex-col gap-1.5">
                    <div className="flex items-center justify-between">
                      <span className="text-[11px] text-gray-400">
                        {showHeatmap ? "AI 병변 분석 히트맵" : "촬영한 원본 사진"}
                      </span>
                      {/* 히트맵이 아직 안 왔거나 실패했으면 전환할 대상이 없으니 버튼도 내보내지 않는다. */}
                      {gradcamImageUrl && !gradcamError && !gradcamLoading && (
                        <button
                          type="button"
                          onClick={() => setImageViewMode(showHeatmap ? "original" : "heatmap")}
                          className={`rounded px-3 py-1.5 text-xs font-medium transition-colors ${
                            showHeatmap
                              ? "bg-orange-600 text-white hover:bg-orange-500"
                              : "bg-gray-700 text-gray-200 hover:bg-gray-600"
                          }`}
                        >
                          {showHeatmap ? "원본 이미지" : "히트맵 보기"}
                        </button>
                      )}
                    </div>

                    <div className="relative min-h-40 rounded border border-gray-700 bg-gray-900 overflow-hidden">
                      {gradcamLoading && (
                        <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 bg-gray-950/80 text-xs text-gray-200">
                          <div className="h-6 w-6 animate-spin rounded-full border-2 border-gray-500 border-t-blue-300" />
                          <span>Grad-CAM 이미지 생성중...</span>
                          <span className="text-[10px] text-gray-500">AI가 주목한 병변 영역을 불러오고 있습니다.</span>
                        </div>
                      )}
                      {displayUrl && (
                        <img
                          src={displayUrl}
                          alt={showHeatmap ? "GradCAM 히트맵" : "촬영한 원본 사진"}
                          className={`w-full ${gradcamLoading ? "opacity-0" : "opacity-100"}`}
                          style={{ display: "block", transition: "opacity 0.15s ease" }}
                          onLoad={() => setGradcamLoading(false)}
                          onError={() => {
                            setGradcamLoading(false);
                            // 원본은 방금 고른 파일의 blob이라 실패할 일이 없다 → 실패했다면 히트맵 쪽이다.
                            if (showHeatmap) setGradcamError(true);
                          }}
                        />
                      )}
                      {showHeatmap && !gradcamLoading && (
                        <p className="px-2 py-1 text-[10px] text-gray-400">
                          🔴 빨간색 — AI가 진단 근거로 삼은 병변 부위 · Grad-CAM 기반
                        </p>
                      )}
                      {gradcamError && (
                        <p className="px-2 py-1 text-[11px] text-yellow-200">
                          Grad-CAM 이미지를 불러오지 못해 촬영한 원본 사진만 표시합니다. 분석 결과는 아래 표를 확인해 주세요.
                        </p>
                      )}
                      <div className="absolute left-2 top-2 z-20 rounded bg-gray-950/80 px-2 py-1 text-[10px] text-gray-300">
                        {showHeatmap ? "Grad-CAM" : "원본"}
                      </div>
                    </div>
                  </div>
                );
              })()}

              <div className="border border-gray-700 rounded overflow-hidden">
                <div className="grid grid-cols-[34px_72px_1fr_64px] bg-gray-950 px-2 py-1.5 text-[10px] font-semibold text-gray-400">
                  <span>순위</span><span>상병코드</span><span>상병명</span><span className="text-right">신뢰도</span>
                </div>
                {result.topK.map((item, idx) => (
                  <div key={item.diseaseCode} className="grid grid-cols-[34px_72px_1fr_64px] items-center border-t border-gray-800 px-2 py-1.5 text-xs">
                    <span className="text-gray-400">{idx + 1}</span>
                    <span className="font-mono text-blue-300">{item.diseaseCode}</span>
                    <span className="text-white">{item.diseaseNameKo}</span>
                    <span className="text-right text-gray-200">{formatConfidence(item.confidence)}</span>
                  </div>
                ))}
              </div>

              {result.aiComment && (
                <div className="rounded bg-gray-800 p-2 text-xs text-gray-200 leading-relaxed">
                  {result.aiComment}
                </div>
              )}

              <p className="rounded border border-yellow-500/40 bg-yellow-500/10 px-2 py-1.5 text-[11px] text-yellow-200">
                {DISCLAIMER}
              </p>

              <Button onClick={handleConfirm} className="w-full">
                확인
              </Button>
            </div>
          )}
        </div>
      </Card>
    </>,
  );
}
