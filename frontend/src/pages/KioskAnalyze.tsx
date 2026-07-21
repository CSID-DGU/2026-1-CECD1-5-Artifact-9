import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { analyzeKiosk, type PreliminaryAnalysis } from "../api/kiosk";
import { Card } from "../components/Card";
import { Button } from "../components/Button";

const DISCLAIMER =
  "⚠️ 본 분석은 AI 보조 참고용이며 의학적 진단이 아닙니다. 결과가 정확하지 않을 수 있으며, 반드시 진료실에서 의사의 확인 진료와 처방을 받으셔야 합니다.";

export default function KioskAnalyze() {
  const { visitId } = useParams<{ visitId: string }>();
  const navigate = useNavigate();

  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PreliminaryAnalysis | null>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0] ?? null;
    setFile(selected);
    setResult(null);
    setError(null);
    setPreviewUrl(selected ? URL.createObjectURL(selected) : null);
  };

  const handleAnalyze = async () => {
    if (!file || !visitId) return;
    setLoading(true);
    setError(null);
    try {
      const response = await analyzeKiosk(Number(visitId), file);
      setResult(response);
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "분석에 실패했습니다. 다시 시도해 주세요.";
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  const handleConfirm = () => {
    navigate("/kiosk");
  };

  return (
    <div className="min-h-screen bg-main-bg text-white text-sm font-medium font-sans flex flex-col">
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
                    type="file"
                    accept="image/*"
                    capture="environment"
                    onChange={handleFileChange}
                    className="w-full text-xs text-gray-200 file:mr-3 file:rounded file:border-0 file:bg-blue-500 file:px-3 file:py-1.5 file:text-white file:text-xs hover:file:bg-blue-600"
                  />
                  <p className="text-center text-[11px] text-gray-400">
                    iPad에서는 버튼을 누르면 후면 카메라로 바로 촬영할 수 있습니다.
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
                    {loading ? "분석 중..." : "분석하기"}
                  </Button>
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

                  {result.gradcamUrl && (
                    <div className="rounded border border-gray-700 bg-gray-900 overflow-hidden">
                      <img src={result.gradcamUrl} alt="GradCAM 히트맵" className="w-full" />
                    </div>
                  )}

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
