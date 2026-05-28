import { useEffect, useMemo, useState } from "react";
import { getLatestAnalysis, requestAnalysis, type AnalysisResponse } from "../api/analysis";
import { listVisitImages, uploadVisitImage, type VisitImage } from "../api/images";
import { getPatient, type Patient } from "../api/patients";
import { getPrescription, savePrescription, type PrescriptionResponse } from "../api/prescription";
import { searchDrugs, searchKcdDiseases } from "../api/reference";
import { completeVisit, diagnoseVisit, getVisit, listVisits, startVisit, type Visit, type VisitStatus } from "../api/visits";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { SearchModal, type SearchItem } from "../components/SearchModal";
import { Table } from "../components/Table";

const STATUS_LABELS: Record<VisitStatus, string> = {
  RECEIVED:   "접수",
  IN_PROGRESS:"진료중",
  ANALYZING:  "분석중",
  ANALYZED:   "분석완료",
  DIAGNOSED:  "진단완료",
  PRESCRIBED: "처방완료",
  COMPLETED:  "진료완료",
  CANCELLED:  "취소",
};

const STATUS_COLORS: Record<VisitStatus, string> = {
  RECEIVED:   "bg-orange-500/20 text-orange-300",
  IN_PROGRESS:"bg-blue-500/20 text-blue-300",
  ANALYZING:  "bg-yellow-500/20 text-yellow-300",
  ANALYZED:   "bg-purple-500/20 text-purple-300",
  DIAGNOSED:  "bg-indigo-500/20 text-indigo-300",
  PRESCRIBED: "bg-green-500/20 text-green-300",
  COMPLETED:  "bg-gray-500/20 text-gray-400",
  CANCELLED:  "bg-red-500/20 text-red-300",
};

// 진료대기: 아직 의사 액션 필요 / 진료완료: 처방 이후만
const WAITING_STATUSES: VisitStatus[]  = ["RECEIVED", "IN_PROGRESS", "ANALYZING", "ANALYZED", "DIAGNOSED"];
const COMPLETED_STATUSES: VisitStatus[] = ["PRESCRIBED", "COMPLETED"];

function formatDateTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
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
  const passed = today.getMonth() > birth.getMonth() ||
    (today.getMonth() === birth.getMonth() && today.getDate() >= birth.getDate());
  if (!passed) age -= 1;
  return `${age}세`;
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "요청 처리 중 오류가 발생했습니다.";
}

export default function Clinic() {
  const [activeTab, setActiveTab]     = useState<"대기" | "완료">("대기");
  const [waitingVisits, setWaiting]   = useState<Visit[]>([]);
  const [completedVisits, setCompleted] = useState<Visit[]>([]);
  const [patientNameMap, setNameMap]  = useState<Map<number, string>>(new Map());
  const [selectedVisit, setSelectedVisit]   = useState<Visit | null>(null);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [images, setImages]           = useState<VisitImage[]>([]);
  const [selectedImageIds, setSelectedImageIds] = useState<number[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl]   = useState<string | null>(null);
  const [analysis, setAnalysis]       = useState<AnalysisResponse | null>(null);
  const [prescription, setPrescription] = useState<PrescriptionResponse | null>(null);

  // 처방 폼
  const [selectedKcd, setSelectedKcd]   = useState<SearchItem | null>(null);
  const [selectedDrug, setSelectedDrug] = useState<SearchItem | null>(null);
  const [drugDosage, setDrugDosage]     = useState("");
  const [drugDays, setDrugDays]         = useState("");
  const [doctorNotes, setDoctorNotes]   = useState("");

  // 모달
  const [isKcdModalOpen, setKcdModalOpen]   = useState(false);
  const [isDrugModalOpen, setDrugModalOpen] = useState(false);

  // 로딩 / 메시지
  const [isLoading, setIsLoading]         = useState(false);
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [message, setMessage]             = useState<string | null>(null);
  const [errorMessage, setErrorMessage]   = useState<string | null>(null);

  const canUploadImage = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  const canAnalyze     = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  // 처방 폼: 분석완료(ANALYZED) 또는 진단완료(DIAGNOSED) 상태에서 바로 표시
  const canPrescribe   = selectedVisit?.status === "ANALYZED" || selectedVisit?.status === "DIAGNOSED";

  // ── 목록 로드 ────────────────────────────────────────────────────
  async function loadVisitLists() {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const allGroups = await Promise.all([
        ...WAITING_STATUSES.map((s)  => listVisits(s)),
        ...COMPLETED_STATUSES.map((s) => listVisits(s)),
      ]);
      const waiting   = allGroups.slice(0, WAITING_STATUSES.length).flat();
      const completed = allGroups.slice(WAITING_STATUSES.length).flat();
      setWaiting(waiting);
      setCompleted(completed);

      // 이름 일괄 조회
      const allVisits  = [...waiting, ...completed];
      const uniqueIds  = [...new Set(allVisits.map((v) => v.patientId))];
      const patients   = await Promise.all(uniqueIds.map((id) => getPatient(id).catch(() => null)));
      const nameMap    = new Map<number, string>();
      uniqueIds.forEach((id, i) => { if (patients[i]) nameMap.set(id, patients[i]!.name); });
      setNameMap(nameMap);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsLoading(false);
    }
  }

  // ── 상세 로드 ────────────────────────────────────────────────────
  async function loadVisitDetail(visitId: number) {
    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);
    setPrescription(null);
    setSelectedKcd(null);
    setSelectedDrug(null);
    setDrugDosage("");
    setDrugDays("");
    setDoctorNotes("");

    try {
      const visit = await getVisit(visitId);
      const [patient, visitImages] = await Promise.all([
        getPatient(visit.patientId),
        listVisitImages(visit.id).catch(() => []),
      ]);
      const latestAnalysis =
        ["ANALYZED", "DIAGNOSED", "PRESCRIBED", "COMPLETED"].includes(visit.status)
          ? await getLatestAnalysis(visit.id).catch(() => null)
          : null;
      const latestPrescription =
        ["PRESCRIBED", "COMPLETED"].includes(visit.status)
          ? await getPrescription(visit.id).catch(() => null)
          : null;

      setSelectedVisit(visit);
      setSelectedPatient(patient);
      setImages(visitImages);
      setSelectedImageIds(visitImages.map((img) => img.imageId));
      setAnalysis(latestAnalysis);
      setPrescription(latestPrescription);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  useEffect(() => { loadVisitLists(); }, []);

  useEffect(() => {
    if (!selectedFile) { setPreviewUrl(null); return; }
    const url = URL.createObjectURL(selectedFile);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [selectedFile]);

  // ── 테이블 데이터 ─────────────────────────────────────────────────
  const activeVisits = activeTab === "대기" ? waitingVisits : completedVisits;

  const currentPatientInfo = useMemo(() => [
    ["이름",     selectedPatient?.name ? <span key="n" className="font-semibold">{selectedPatient.name}</span> : "-"],
    ["환자번호", selectedPatient ? `P${String(selectedPatient.id).padStart(5, "0")}` : "-"],
    ["나이",     calculateAge(selectedPatient?.birthDate)],
    ["성별",     formatGender(selectedPatient?.gender)],
    ["연락처",   selectedPatient?.phone ?? "-"],
    ["메모",     selectedPatient?.memo ?? "-"],
    ["접수번호", selectedVisit ? `V${String(selectedVisit.id).padStart(5, "0")}` : "-"],
    ["현재상태", selectedVisit ? STATUS_LABELS[selectedVisit.status] : "-"],
  ], [selectedPatient, selectedVisit]);

  // 환자번호 컬럼 제거 → 이름 + 5컬럼으로 줄여 선택 버튼이 잘리지 않도록
  const centerTableData = activeVisits.map((visit, idx) => [
    idx + 1,
    `V${String(visit.id).padStart(5, "0")}`,
    patientNameMap.get(visit.patientId) ?? "-",
    formatDateTime(visit.visitDate),
    <span key={`st-${visit.id}`} className={`px-2 py-0.5 rounded text-[10px] whitespace-nowrap ${STATUS_COLORS[visit.status]}`}>
      {STATUS_LABELS[visit.status]}
    </span>,
    <button
      key={`sel-${visit.id}`}
      onClick={() => loadVisitDetail(visit.id)}
      className={`px-2 py-0.5 border text-[10px] rounded cursor-pointer transition-colors whitespace-nowrap ${
        selectedVisit?.id === visit.id
          ? "bg-blue-600 border-blue-500 text-white"
          : "bg-gray-800 hover:bg-gray-700 border-gray-600"
      }`}
    >
      선택
    </button>,
  ]);

  // ── 핸들러 ───────────────────────────────────────────────────────
  async function handleStartVisit() {
    if (!selectedVisit) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      const v = await startVisit(selectedVisit.id);
      setSelectedVisit(v);
      await loadVisitLists();
      setMessage("진료를 시작했습니다. 이미지를 업로드하세요.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  async function handleUploadImage() {
    if (!selectedVisit || !selectedFile) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      const uploaded = await uploadVisitImage(selectedVisit.id, selectedFile);
      const refreshed = await listVisitImages(selectedVisit.id);
      setImages(refreshed);
      setSelectedImageIds((cur) => Array.from(new Set([...cur, uploaded.imageId])));
      setSelectedFile(null);
      setMessage("이미지를 업로드했습니다.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  async function handleAnalyze() {
    if (!selectedVisit || selectedImageIds.length === 0) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      const result = await requestAnalysis(selectedVisit.id, selectedImageIds);
      setAnalysis(result);
      setSelectedVisit((cur) => cur ? { ...cur, status: "ANALYZED" } : cur);
      await loadVisitLists();
      setMessage("AI 분석이 완료되었습니다. 결과를 확인하고 처방을 입력하세요.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  async function handleSavePrescription() {
    if (!selectedVisit || !selectedKcd || !selectedDrug) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      // ANALYZED 상태면 진단확정 먼저 (내부 처리, UI 버튼 없음)
      let visit = selectedVisit;
      if (visit.status === "ANALYZED") {
        visit = await diagnoseVisit(visit.id);
        setSelectedVisit(visit);
      }

      const saved = await savePrescription(visit.id, {
        kcdDiseaseId: selectedKcd.id,
        analysisId:   analysis?.analysisId ?? null,
        doctorNotes:  doctorNotes.trim() || null,
        details: [{
          medicineName: selectedDrug.nameKr,
          dosage:       drugDosage.trim() || null,
          durationDays: drugDays ? Number(drugDays) : null,
        }],
      });
      setPrescription(saved);
      setSelectedVisit((cur) => cur ? { ...cur, status: "PRESCRIBED" } : cur);
      await loadVisitLists();
      setMessage("처방이 저장되었습니다. 진료를 완료하세요.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  async function handleComplete() {
    if (!selectedVisit) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      const v = await completeVisit(selectedVisit.id);
      setSelectedVisit(v);
      await loadVisitLists();
      setMessage("진료가 완료되었습니다.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  function toggleSelectedImage(imageId: number) {
    setSelectedImageIds((cur) =>
      cur.includes(imageId) ? cur.filter((id) => id !== imageId) : [...cur, imageId]
    );
  }

  const status = selectedVisit?.status;

  return (
    <>
      {/* KCD 상병코드 모달 */}
      <SearchModal
        isOpen={isKcdModalOpen}
        onClose={() => setKcdModalOpen(false)}
        onSelect={(item) => setSelectedKcd(item)}
        title="KCD 상병코드 검색"
        placeholder="상병명 검색 (예: 흑색종, 건선, 습진)"
        onSearch={searchKcdDiseases}
      />

      {/* 약품 검색 모달 */}
      <SearchModal
        isOpen={isDrugModalOpen}
        onClose={() => setDrugModalOpen(false)}
        onSelect={(item) => setSelectedDrug(item)}
        title="약품 검색"
        placeholder="약품명 검색 (예: 타크로리무스, 스테로이드)"
        onSearch={searchDrugs}
      />

      <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">

        {/* Left Column */}
        <section className="w-[300px] flex flex-col shrink-0">
          <Card title="환자 정보" className="flex-1">
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-2 px-1">
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500" />
                </span>
                <span className="text-[11px] text-green-400 font-medium">진료중</span>
              </div>
              <Table headers={["항목", "내용"]} data={currentPatientInfo} />
            </div>
          </Card>
        </section>

        {/* Center Column */}
        <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
          <Card title="진료 현황" className="shrink-0">
            <div className="flex border-b border-gray-700 mb-3 pb-1 gap-2">
              {(["대기", "완료"] as const).map((tab) => (
                <button
                  key={tab}
                  onClick={() => setActiveTab(tab)}
                  className={`px-4 py-1.5 text-xs font-medium rounded transition-colors cursor-pointer ${
                    activeTab === tab ? "bg-blue-600 text-white font-bold" : "bg-gray-800 text-gray-400 hover:bg-gray-700"
                  }`}
                >
                  {tab === "대기" ? "진료대기" : "진료완료"}
                </button>
              ))}
            </div>

            {errorMessage && (
              <p className="mb-3 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-xs text-red-200">{errorMessage}</p>
            )}
            {message && (
              <p className="mb-3 rounded border border-blue-500/40 bg-blue-500/10 px-3 py-2 text-xs text-blue-100">{message}</p>
            )}
            {isLoading && <p className="mb-3 text-xs text-gray-400">목록 로딩 중...</p>}

            <Table
              headers={["순번", "접수번호", "이름", "접수시간", "현재상태", "선택"]}
              data={centerTableData}
            />
          </Card>

          <Card title="이미지 업로드 및 선택" className="flex-1">
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-3 flex-wrap">
                <label className={`px-3 py-2 rounded-lg text-xs transition-colors ${
                  canUploadImage ? "bg-blue-600 hover:bg-blue-500 cursor-pointer" : "bg-gray-700 text-gray-400 cursor-not-allowed"
                }`}>
                  파일 선택
                  <input
                    type="file" accept="image/*"
                    disabled={!canUploadImage || isActionLoading}
                    onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
                    className="hidden"
                  />
                </label>
                <span className="text-xs text-gray-300">{selectedFile?.name ?? "선택된 파일 없음"}</span>
                <Button onClick={handleUploadImage} disabled={!selectedFile || !canUploadImage || isActionLoading} className="py-1.5 text-xs">
                  업로드
                </Button>
                <Button onClick={handleAnalyze} disabled={!canAnalyze || selectedImageIds.length === 0 || isActionLoading} className="py-1.5 text-xs">
                  AI 분석
                </Button>
              </div>

              {!selectedVisit && (
                <p className="text-xs text-gray-400">진료 현황에서 접수를 선택하면 이미지 업로드와 AI 분석을 진행할 수 있습니다.</p>
              )}
              {status === "RECEIVED" && (
                <div className="flex items-center gap-3 rounded border border-orange-500/30 bg-orange-500/10 px-3 py-2">
                  <p className="flex-1 text-xs text-orange-100">이미지 업로드 전 진료를 시작해야 합니다.</p>
                  <Button onClick={handleStartVisit} disabled={isActionLoading} className="py-1.5 text-xs">진료 시작</Button>
                </div>
              )}

              {previewUrl && (
                <div className="w-full max-w-[360px] overflow-hidden rounded border border-gray-700 bg-gray-900">
                  <img src={previewUrl} alt="미리보기" className="h-56 w-full object-contain" />
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
                      <img src={image.imageUrl} alt={`이미지 ${image.imageId}`} className="h-28 w-full object-cover" />
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
          {/* AI 분석 결과 */}
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
                      신뢰도 {(Number(analysis.top1.confidence) * 100).toFixed(1)}%
                    </p>
                  </div>
                  <Table
                    headers={["순위", "상병코드", "상병명", "신뢰도"]}
                    data={analysis.top5.map((item) => [
                      item.rank, item.diseaseCode, item.diseaseNameKo,
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

          {/* 처방 카드 — 분석완료(ANALYZED) 이후 바로 표시, 진단확정 버튼 없음 */}
          {selectedVisit && ["ANALYZED", "DIAGNOSED", "PRESCRIBED", "COMPLETED"].includes(selectedVisit.status) && (
            <Card title="처방">
              <div className="flex flex-col gap-4">

                {/* 처방 입력 폼 — ANALYZED 또는 DIAGNOSED */}
                {canPrescribe && (
                  <div className="flex flex-col gap-3">
                    {status === "DIAGNOSED" && (
                      <p className="text-[10px] text-indigo-400 font-medium">진단 확정됨</p>
                    )}

                    {/* KCD 상병코드 */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-[10px] text-gray-400">
                        KCD 상병코드 <span className="text-red-400">*</span>
                      </span>
                      {selectedKcd ? (
                        <div className="flex items-center gap-2 rounded border border-blue-500/40 bg-blue-500/10 px-3 py-2">
                          <span className="font-mono text-xs text-blue-300">{selectedKcd.code}</span>
                          <span className="text-xs text-white flex-1">{selectedKcd.nameKr}</span>
                          <button
                            onClick={() => setSelectedKcd(null)}
                            className="text-[10px] text-gray-400 hover:text-red-400 transition-colors"
                          >
                            변경
                          </button>
                        </div>
                      ) : (
                        <button
                          onClick={() => setKcdModalOpen(true)}
                          className="w-full px-3 py-2 rounded border border-dashed border-gray-600 text-xs text-gray-400 hover:border-blue-500 hover:text-blue-300 transition-colors text-left"
                        >
                          + 상병코드 검색 (클릭하여 선택)
                        </button>
                      )}
                    </div>

                    {/* 약품 */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-[10px] text-gray-400">
                        처방 약품 <span className="text-red-400">*</span>
                      </span>
                      {selectedDrug ? (
                        <div className="flex flex-col gap-2">
                          <div className="flex items-center gap-2 rounded border border-blue-500/40 bg-blue-500/10 px-3 py-2">
                            <span className="font-mono text-xs text-blue-300">{selectedDrug.code}</span>
                            <span className="text-xs text-white flex-1">{selectedDrug.nameKr}</span>
                            <button
                              onClick={() => setSelectedDrug(null)}
                              className="text-[10px] text-gray-400 hover:text-red-400 transition-colors"
                            >
                              변경
                            </button>
                          </div>
                          <div className="flex gap-2">
                            <input
                              type="text"
                              value={drugDosage}
                              onChange={(e) => setDrugDosage(e.target.value)}
                              placeholder="용법 (예: 1일 2회 도포)"
                              className="flex-1 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-xs text-white focus:outline-none focus:border-blue-500"
                            />
                            <input
                              type="number"
                              value={drugDays}
                              onChange={(e) => setDrugDays(e.target.value)}
                              placeholder="기간(일)"
                              className="w-20 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-xs text-white focus:outline-none focus:border-blue-500"
                            />
                          </div>
                        </div>
                      ) : (
                        <button
                          onClick={() => setDrugModalOpen(true)}
                          className="w-full px-3 py-2 rounded border border-dashed border-gray-600 text-xs text-gray-400 hover:border-blue-500 hover:text-blue-300 transition-colors text-left"
                        >
                          + 약품 검색 (클릭하여 선택)
                        </button>
                      )}
                    </div>

                    {/* 의사 소견 */}
                    <div className="flex flex-col gap-1">
                      <span className="text-[10px] text-gray-400">의사 소견 (선택)</span>
                      <textarea
                        value={doctorNotes}
                        onChange={(e) => setDoctorNotes(e.target.value)}
                        placeholder="소견을 입력하세요"
                        rows={2}
                        className="px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-xs text-white focus:outline-none focus:border-blue-500 resize-none"
                      />
                    </div>

                    <Button
                      onClick={handleSavePrescription}
                      disabled={!selectedKcd || !selectedDrug || isActionLoading}
                      className="py-2 text-xs w-full"
                    >
                      처방 저장
                    </Button>
                  </div>
                )}

                {/* PRESCRIBED: 처방 내용 + 진료 완료 */}
                {status === "PRESCRIBED" && prescription && (
                  <div className="flex flex-col gap-3">
                    <p className="text-[10px] text-blue-400 font-medium">처방 저장됨</p>
                    <div className="rounded border border-gray-600 bg-side-bg p-3 flex flex-col gap-1.5">
                      <div className="flex gap-2 text-xs">
                        <span className="text-gray-400 w-16 shrink-0">상병코드</span>
                        <span className="text-white">{prescription.kcdCode} {prescription.kcdNameKr}</span>
                      </div>
                      {prescription.details.map((d, i) => (
                        <div key={i} className="flex gap-2 text-xs">
                          <span className="text-gray-400 w-16 shrink-0">약품{i + 1}</span>
                          <span className="text-white">
                            {d.medicineName}{d.dosage ? ` / ${d.dosage}` : ""}{d.durationDays ? ` / ${d.durationDays}일` : ""}
                          </span>
                        </div>
                      ))}
                      {prescription.doctorNotes && (
                        <div className="flex gap-2 text-xs">
                          <span className="text-gray-400 w-16 shrink-0">소견</span>
                          <span className="text-white">{prescription.doctorNotes}</span>
                        </div>
                      )}
                    </div>
                    <Button onClick={handleComplete} disabled={isActionLoading} className="py-2 text-xs w-full">
                      진료 완료
                    </Button>
                  </div>
                )}

                {/* COMPLETED */}
                {status === "COMPLETED" && prescription && (
                  <div className="flex flex-col gap-3">
                    <p className="text-[10px] text-gray-400 font-medium">진료 완료</p>
                    <div className="rounded border border-gray-600 bg-side-bg p-3 flex flex-col gap-1.5">
                      <div className="flex gap-2 text-xs">
                        <span className="text-gray-400 w-16 shrink-0">상병코드</span>
                        <span className="text-white">{prescription.kcdCode} {prescription.kcdNameKr}</span>
                      </div>
                      {prescription.details.map((d, i) => (
                        <div key={i} className="flex gap-2 text-xs">
                          <span className="text-gray-400 w-16 shrink-0">약품{i + 1}</span>
                          <span className="text-white">
                            {d.medicineName}{d.dosage ? ` / ${d.dosage}` : ""}{d.durationDays ? ` / ${d.durationDays}일` : ""}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </Card>
          )}

          {/* 진료정보 */}
          <Card title="진료정보" className="flex-1">
            <div className="grid grid-cols-2 gap-x-6 gap-y-3">
              <InfoItem label="접수번호"    value={selectedVisit  ? `V${String(selectedVisit.id).padStart(5, "0")}` : "-"} />
              <InfoItem label="환자번호"    value={selectedPatient ? `P${String(selectedPatient.id).padStart(5, "0")}` : "-"} />
              <InfoItem label="접수시간"    value={formatDateTime(selectedVisit?.visitDate)} />
              <InfoItem label="진료상태"    value={selectedVisit ? STATUS_LABELS[selectedVisit.status] : "-"} />
              <InfoItem label="업로드 이미지" value={images.length > 0 ? `${images.length}장` : "-"} />
              <InfoItem label="분석 결과"   value={analysis ? `${analysis.top5.length}개 후보` : "-"} />
            </div>
          </Card>
        </section>

      </div>
    </>
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
