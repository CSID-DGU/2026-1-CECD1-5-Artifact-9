import { useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { analyzeKiosk, type PreliminaryAnalysis } from "../api/kiosk";
import { Card } from "../components/Card";
import { Button } from "../components/Button";

const DISCLAIMER =
  "⚠️ 본 분석은 AI 보조 참고용이며 의학적 진단이 아닙니다. 결과가 정확하지 않을 수 있으며, 반드시 진료실에서 의사의 확인 진료와 처방을 받으셔야 합니다.";
const MIN_ANALYSIS_LOADING_MS = 1200;

export default function KioskAnalyze() {
  const { visitId } = useParams<{ visitId: string }>();
  const navigate = useNavigate();
  const galleryInputRef = useRef<HTMLInputElement | null>(null);
  const cameraInputRef = useRef<HTMLInputElement | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [gradcamLoading, setGradcamLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [gradcamError, setGradcamError] = useState(false);
  const [result, setResult] = useState<PreliminaryAnalysis | null>(null);

  const gradcamImageUrl = result
    ? `${result.gradcamUrl ?? `/api/kiosk/preliminary/${visitId}/heatmap`}?t=${encodeURIComponent(result.analyzedAt)}`
    : null;

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0] ?? null;
    setFile(selected);
    setResult(null);
    setError(null);
    setGradcamError(false);
    setGradcamLoading(false);
    setPreviewUrl(selected ? URL.createObjectURL(selected) : null);
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
    if (!file || !visitId) return;
    setLoading(true);
    setGradcamLoading(true);
    setGradcamError(false);
    setError(null);
    try {
      const [response] = await Promise.all([
        analyzeKiosk(Number(visitId), file),
        new Promise((resolve) => window.setTimeout(resolve, MIN_ANALYSIS_LOADING_MS)),
      ]);
      setResult(response);
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "분석에 실패했습니다. 다시 시도해 주세요.";
      setError(message);
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

  return (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col">
      {loading && renderLoadingOverlay()}

      <header className="h-10 bg-blue-500 flex items-center px-4 shrink-0">
        <span className="text-blue-200 text-xs">AI 보조 진단 시스템 · 대기실 키오스크</span>
      </header>

      <main className="flex flex-1 justify-center p-4 overflow-y-auto">
        <div className="flex w-full max-w-md flex-col gap-2">
          <Card title="피부 사진 예비 분석" contentClassName="!p-3">
            <div className="flex flex-col gap-3">
              <p className="rounded border border-yellow-500/40 bg-yellow-500/10 px-2 py-1.5 text-[11px] text-yellow-200">
                {DISCLAIMER}
              </p>

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
                    {file ? file.name : "갤러리에서 선택하거나 iPad 카메라로 촬영해 주세요."}
                  </p>
                  <p className="text-center text-[11px] text-gray-400">
                    사진 촬영은 iPad에서 후면 카메라를 열고, 파일 선택은 사진 보관함에서 이미지를 선택합니다.
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
                        신뢰도 {(result.topK[0].confidence * 100).toFixed(1)}%
                      </p>
                    </div>
                  )}

                  <div className="relative min-h-40 rounded border border-gray-700 bg-gray-900 overflow-hidden">
                    {gradcamLoading && (
                      <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 bg-gray-950/80 text-xs text-gray-200">
                        <div className="h-6 w-6 animate-spin rounded-full border-2 border-gray-500 border-t-blue-300" />
                        <span>Grad-CAM 이미지 생성중...</span>
                        <span className="text-[10px] text-gray-500">AI가 주목한 병변 영역을 불러오고 있습니다.</span>
                      </div>
                    )}
                    {gradcamImageUrl && (
                      <img
                        src={gradcamImageUrl}
                        alt="GradCAM 히트맵"
                        className={`w-full ${gradcamLoading ? "opacity-0" : "opacity-100"}`}
                        onLoad={() => setGradcamLoading(false)}
                        onError={() => {
                          setGradcamLoading(false);
                          setGradcamError(true);
                        }}
                      />
                    )}
                    {gradcamError && (
                      <div className="flex min-h-40 items-center justify-center px-4 text-center text-xs text-yellow-200">
                        Grad-CAM 이미지를 불러오지 못했습니다. 진료실 화면에서 다시 확인해 주세요.
                      </div>
                    )}
                    <div className="absolute left-2 top-2 z-20 rounded bg-gray-950/80 px-2 py-1 text-[10px] text-gray-300">
                      Grad-CAM
                    </div>
                  </div>

                  <div className="border border-gray-700 rounded overflow-hidden">
                    <div className="grid grid-cols-[34px_72px_1fr_64px] bg-gray-950 px-2 py-1.5 text-[10px] font-semibold text-gray-400">
                      <span>순위</span><span>상병코드</span><span>상병명</span><span className="text-right">신뢰도</span>
                    </div>
                    {result.topK.map((item, idx) => (
                      <div key={item.diseaseCode} className="grid grid-cols-[34px_72px_1fr_64px] items-center border-t border-gray-800 px-2 py-1.5 text-xs">
                        <span className="text-gray-400">{idx + 1}</span>
                        <span className="font-mono text-blue-300">{item.diseaseCode}</span>
                        <span className="text-white">{item.diseaseNameKo}</span>
                        <span className="text-right text-gray-200">{(item.confidence * 100).toFixed(1)}%</span>
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
        </div>
      </main>
    </div>
  );
}
