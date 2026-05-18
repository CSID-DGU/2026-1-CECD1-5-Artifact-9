import { useEffect, useMemo, useState } from "react";
import { getLatestAnalysis, requestAnalysis, type AnalysisResponse } from "../api/analysis";
import { listVisitImages, uploadVisitImage, type VisitImage } from "../api/images";
import { getPatient, type Patient } from "../api/patients";
import { getVisit, listVisits, startVisit, type Visit, type VisitStatus } from "../api/visits";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Table } from "../components/Table";

const STATUS_LABELS: Record<VisitStatus, string> = {
  RECEIVED: "접수",
  IN_PROGRESS: "진료중",
  ANALYZING: "분석중",
  ANALYZED: "분석완료",
  DIAGNOSED: "진단완료",
  PRESCRIBED: "처방완료",
  COMPLETED: "진료완료",
  CANCELLED: "취소",
};

const COMPLETED_STATUSES: VisitStatus[] = ["ANALYZED", "DIAGNOSED", "PRESCRIBED", "COMPLETED"];

function formatDateTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatGender(gender?: Patient["gender"] | null) {
  if (gender === "M" || gender === "MALE") return "남";
  if (gender === "F" || gender === "FEMALE") return "여";
  if (gender === "OTHER") return "기타";
  return "-";
}

function calculateAge(birthDate?: string | null) {
  if (!birthDate) return "-";
  const birth = new Date(birthDate);
  if (Number.isNaN(birth.getTime())) return "-";

  const today = new Date();
  let age = today.getFullYear() - birth.getFullYear();
  const hasBirthdayPassed =
    today.getMonth() > birth.getMonth() ||
    (today.getMonth() === birth.getMonth() && today.getDate() >= birth.getDate());

  if (!hasBirthdayPassed) age -= 1;
  return `${age}세`;
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "요청 처리 중 오류가 발생했습니다.";
}

export default function Clinic() {
  const [activeTab, setActiveTab] = useState<"대기" | "완료">("대기");
  const [waitingVisits, setWaitingVisits] = useState<Visit[]>([]);
  const [completedVisits, setCompletedVisits] = useState<Visit[]>([]);
  const [selectedVisit, setSelectedVisit] = useState<Visit | null>(null);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [images, setImages] = useState<VisitImage[]>([]);
  const [selectedImageIds, setSelectedImageIds] = useState<number[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<AnalysisResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const canUploadImage = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  const canAnalyze = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";

  async function loadVisitLists() {
    setIsLoading(true);
    setErrorMessage(null);

    try {
      const [received, inProgress, ...completedGroups] = await Promise.all([
        listVisits("RECEIVED"),
        listVisits("IN_PROGRESS"),
        ...COMPLETED_STATUSES.map((status) => listVisits(status)),
      ]);

      setWaitingVisits([...received, ...inProgress]);
      setCompletedVisits(completedGroups.flat());
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsLoading(false);
    }
  }

  async function loadVisitDetail(visitId: number) {
    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const visit = await getVisit(visitId);
      const [patient, visitImages] = await Promise.all([
        getPatient(visit.patientId),
        listVisitImages(visit.id).catch(() => []),
      ]);

      const latestAnalysis =
        visit.status === "ANALYZED"
          ? await getLatestAnalysis(visit.id).catch(() => null)
          : null;

      setSelectedVisit(visit);
      setSelectedPatient(patient);
      setImages(visitImages);
      setSelectedImageIds(visitImages.map((image) => image.imageId));
      setAnalysis(latestAnalysis);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  useEffect(() => {
    loadVisitLists();
  }, []);

  useEffect(() => {
    if (!selectedFile) {
      setPreviewUrl(null);
      return;
    }

    const objectUrl = URL.createObjectURL(selectedFile);
    setPreviewUrl(objectUrl);

    return () => URL.revokeObjectURL(objectUrl);
  }, [selectedFile]);

  const activeVisits = activeTab === "대기" ? waitingVisits : completedVisits;

  const currentPatientInfo = useMemo(
    () => [
      ["이름", selectedPatient?.name ? <span key="name" className="font-semibold">{selectedPatient.name}</span> : "-"],
      ["환자번호", selectedPatient ? `P${String(selectedPatient.id).padStart(5, "0")}` : "-"],
      ["나이", calculateAge(selectedPatient?.birthDate)],
      ["성별", formatGender(selectedPatient?.gender)],
      ["연락처", selectedPatient?.phone ?? "-"],
      ["메모", selectedPatient?.memo ?? "-"],
      ["접수번호", selectedVisit ? `V${String(selectedVisit.id).padStart(5, "0")}` : "-"],
      ["현재상태", selectedVisit ? STATUS_LABELS[selectedVisit.status] : "-"],
    ],
    [selectedPatient, selectedVisit]
  );

  const centerTableData = activeVisits.map((visit, idx) => [
      idx + 1,
      `V${String(visit.id).padStart(5, "0")}`,
      `P${String(visit.patientId).padStart(5, "0")}`,
      formatDateTime(visit.visitDate),
      <span key={`status-${visit.id}`} className={`px-2 py-0.5 rounded text-[10px] ${visit.status === "RECEIVED" ? "bg-orange-500/20 text-orange-300" : "bg-green-500/20 text-green-300"}`}>
        {STATUS_LABELS[visit.status]}
      </span>,
      <button 
        key={`select-${visit.id}`}
        onClick={() => loadVisitDetail(visit.id)}
        className={`px-2 py-0.5 border text-[10px] rounded cursor-pointer transition-colors ${
          selectedVisit?.id === visit.id
            ? "bg-blue-600 border-blue-500 text-white"
            : "bg-gray-800 hover:bg-gray-700 border-gray-600"
        }`}
      >
        선택
      </button>
    ]);

  async function handleStartVisit() {
    if (!selectedVisit) return;

    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const updatedVisit = await startVisit(selectedVisit.id);
      setSelectedVisit(updatedVisit);
      await loadVisitLists();
      setMessage("진료를 시작했습니다. 이제 이미지를 업로드할 수 있습니다.");
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  async function handleUploadImage() {
    if (!selectedVisit || !selectedFile) return;

    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const uploadedImage = await uploadVisitImage(selectedVisit.id, selectedFile);
      const refreshedImages = await listVisitImages(selectedVisit.id);
      setImages(refreshedImages);
      setSelectedImageIds((current) => Array.from(new Set([...current, uploadedImage.imageId])));
      setSelectedFile(null);
      setMessage("이미지를 업로드했습니다.");
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  async function handleAnalyze() {
    if (!selectedVisit || selectedImageIds.length === 0) return;

    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const result = await requestAnalysis(selectedVisit.id, selectedImageIds);
      setAnalysis(result);
      setSelectedVisit((current) => current ? { ...current, status: "ANALYZED" } : current);
      await loadVisitLists();
      setMessage("AI 분석 요청이 완료되었습니다.");
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  function toggleSelectedImage(imageId: number) {
    setSelectedImageIds((current) =>
      current.includes(imageId)
        ? current.filter((id) => id !== imageId)
        : [...current, imageId]
    );
  }

  return (
      <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">
        
        {/* Left Column */}
        <section className="w-[300px] flex flex-col shrink-0">
          <Card title="환자 정보" className="flex-1">
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-2 px-1">
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
              </span>
              <span className="text-[11px] text-green-400 font-medium">진료중</span>
            </div>
            
            <Table 
              headers={["항목", "내용"]} 
              data={currentPatientInfo} 
            />
          </div>
        </Card>
        </section>

        {/* Center Column */}
        <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
          <Card title="진료 현황" className="shrink-0">
          {/* 상태 버튼 */}
          <div className="flex border-b border-gray-700 mb-3 pb-1 gap-2">
            <button
              onClick={() => setActiveTab("대기")}
              className={`px-4 py-1.5 text-xs font-medium rounded transition-colors cursor-pointer ${
                activeTab === "대기" 
                  ? "bg-blue-600 text-white font-bold" 
                  : "bg-gray-800 text-gray-400 hover:bg-gray-700"
              }`}
            >
              진료대기
            </button>
            <button
              onClick={() => setActiveTab("완료")}
              className={`px-4 py-1.5 text-xs font-medium rounded transition-colors cursor-pointer ${
                activeTab === "완료" 
                  ? "bg-blue-600 text-white font-bold" 
                  : "bg-gray-800 text-gray-400 hover:bg-gray-700"
              }`}
            >
              진료완료
            </button>
          </div>

          {errorMessage && (
            <p className="mb-3 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-xs text-red-200">
              {errorMessage}
            </p>
          )}
          {message && (
            <p className="mb-3 rounded border border-blue-500/40 bg-blue-500/10 px-3 py-2 text-xs text-blue-100">
              {message}
            </p>
          )}
          {isLoading && <p className="mb-3 text-xs text-gray-300">접수 목록을 불러오는 중입니다.</p>}

          {/* 환자 테이블 */}
          <Table 
            headers={["순번", "접수번호", "환자번호", "접수시간", "현재상태", "선택"]} 
            data={centerTableData} 
          />
        </Card>

        <Card title="이미지 업로드 및 선택" className="flex-1">
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-3">
              <label className={`px-3 py-2 rounded-lg text-xs transition-colors ${
                canUploadImage
                  ? "bg-blue-600 hover:bg-blue-500 cursor-pointer"
                  : "bg-gray-700 text-gray-400 cursor-not-allowed"
              }`}>
                파일 선택
                <input
                  type="file"
                  accept="image/*"
                  disabled={!canUploadImage || isActionLoading}
                  onChange={(event) => setSelectedFile(event.target.files?.[0] ?? null)}
                  className="hidden"
                />
              </label>
              <span className="text-xs text-gray-300">
                {selectedFile?.name ?? "선택된 파일 없음"}
              </span>
              <Button
                onClick={handleUploadImage}
                disabled={!selectedFile || !canUploadImage || isActionLoading}
                className="py-1.5 text-xs"
              >
                업로드
              </Button>
              <Button
                onClick={handleAnalyze}
                disabled={!canAnalyze || selectedImageIds.length === 0 || isActionLoading}
                className="py-1.5 text-xs"
              >
                AI 분석
              </Button>
            </div>

            {!selectedVisit && (
              <p className="text-xs text-gray-400">진료 현황에서 접수를 선택하면 이미지 업로드와 AI 분석을 진행할 수 있습니다.</p>
            )}
            {selectedVisit?.status === "RECEIVED" && (
              <div className="flex items-center gap-3 rounded border border-orange-500/30 bg-orange-500/10 px-3 py-2">
                <p className="flex-1 text-xs text-orange-100">이미지 업로드 전 진료를 시작해야 합니다.</p>
                <Button onClick={handleStartVisit} disabled={isActionLoading} className="py-1.5 text-xs">
                  진료 시작
                </Button>
              </div>
            )}

            {previewUrl && (
              <div className="w-full max-w-[360px] overflow-hidden rounded border border-gray-700 bg-gray-900">
                <img src={previewUrl} alt="선택 이미지 미리보기" className="h-56 w-full object-contain" />
              </div>
            )}

            <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
              {images.map((image) => {
                const selected = selectedImageIds.includes(image.imageId);

                return (
                  <button
                    key={image.imageId}
                    onClick={() => toggleSelectedImage(image.imageId)}
                    className={`overflow-hidden rounded border text-left transition-colors ${
                      selected ? "border-blue-500 bg-blue-500/10" : "border-gray-700 bg-gray-900"
                    }`}
                  >
                    <img src={image.imageUrl} alt={`업로드 이미지 ${image.imageId}`} className="h-28 w-full object-cover" />
                    <div className="px-2 py-1 text-[10px] text-gray-300">
                      #{image.imageId} · {formatDateTime(image.uploadedAt)}
                    </div>
                  </button>
                );
              })}
            </div>
          </div>
        </Card>
        </section>

        {/* Right Column */}
        <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
          <Card title="AI 분석 결과">
            <div className="flex flex-col gap-3">
              {!analysis ? (
                <p className="text-xs text-gray-300">이미지를 선택하고 AI 분석을 요청하면 결과가 표시됩니다.</p>
              ) : (
                <>
                  <div className="rounded border border-blue-500/30 bg-blue-500/10 p-3">
                    <p className="text-xs text-gray-300">Top 1</p>
                    <p className="mt-1 text-sm font-semibold text-white">
                      {analysis.top1.diseaseNameKo} ({analysis.top1.diseaseCode})
                    </p>
                    <p className="mt-1 text-xs text-blue-100">
                      신뢰도 {(analysis.top1.confidence * 100).toFixed(1)}%
                    </p>
                  </div>
                  <Table
                    headers={["순위", "상병코드", "상병명", "신뢰도"]}
                    data={analysis.top5.map((item) => [
                      item.rank,
                      item.diseaseCode,
                      item.diseaseNameKo,
                      `${(item.confidence * 100).toFixed(1)}%`,
                    ])}
                  />
                  <p className="text-[10px] text-gray-400">
                    모델 {analysis.modelVersion} · {analysis.inferenceTimeMs}ms · {formatDateTime(analysis.analyzedAt)}
                  </p>
                </>
              )}
            </div>
          </Card>

          <Card title="진료정보" className="flex-1">
            <div className="grid grid-cols-2 gap-x-6 gap-y-3">
              <InfoItem label="진료과목" value="피부과" />
              <InfoItem label="진료의사" value="홍길동" />
              <InfoItem label="초/재진" value={selectedVisit ? "초진" : "-"} />
              <InfoItem label="접수시간" value={formatDateTime(selectedVisit?.visitDate)} />
              <InfoItem label="진료상태" value={selectedVisit ? STATUS_LABELS[selectedVisit.status] : "-"} />
              <InfoItem label="업로드 이미지" value={`${images.length}장`} />
            </div>
          </Card>
        </section>

      </div>
  );
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className="w-20 shrink-0 text-right text-xs text-gray-200">{label}</span>
      <span className="min-h-[30px] flex-1 rounded border border-gray-600 bg-side-bg px-3 py-1.5 text-xs text-gray-100">
        {value}
      </span>
    </div>
  );
}
