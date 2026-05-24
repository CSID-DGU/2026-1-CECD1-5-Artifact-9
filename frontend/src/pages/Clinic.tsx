import { useEffect, useMemo, useState } from "react";
import { getLatestAnalysis, requestAnalysis, type AnalysisResponse } from "../api/analysis";
import { listVisitImages, uploadVisitImage, type VisitImage } from "../api/images";
import { getPatient, type Patient } from "../api/patients";
import {
  getPrescription,
  savePrescription,
  type PrescriptionDetailRequest,
  type PrescriptionResponse,
} from "../api/prescriptions";
import { searchDrugs, searchKcdDiseases, type DrugMaster, type KcdDisease } from "../api/reference";
import {
  completeVisit,
  diagnoseVisit,
  getVisit,
  listVisits,
  startVisit,
  type Visit,
  type VisitStatus,
} from "../api/visits";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Input } from "../components/Input";
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

type PrescriptionDraftDetail = PrescriptionDetailRequest & {
  localId: string;
};

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
  const [prescription, setPrescription] = useState<PrescriptionResponse | null>(null);
  const [diseaseQuery, setDiseaseQuery] = useState("");
  const [diseaseResults, setDiseaseResults] = useState<KcdDisease[]>([]);
  const [selectedDisease, setSelectedDisease] = useState<KcdDisease | null>(null);
  const [drugQuery, setDrugQuery] = useState("");
  const [drugResults, setDrugResults] = useState<DrugMaster[]>([]);
  const [prescriptionDetails, setPrescriptionDetails] = useState<PrescriptionDraftDetail[]>([]);
  const [doctorNotes, setDoctorNotes] = useState("");
  const [revisitRecommendedDate, setRevisitRecommendedDate] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const canUploadImage = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  const canAnalyze = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  const canDiagnose = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  const canSavePrescription = selectedVisit?.status === "DIAGNOSED";
  const canCompleteVisit = selectedVisit?.status === "PRESCRIBED";

  function resetPrescriptionForm() {
    setDiseaseQuery("");
    setDiseaseResults([]);
    setSelectedDisease(null);
    setDrugQuery("");
    setDrugResults([]);
    setPrescriptionDetails([]);
    setDoctorNotes("");
    setRevisitRecommendedDate("");
    setPrescription(null);
  }

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
    resetPrescriptionForm();

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
      const savedPrescription =
        visit.status === "PRESCRIBED" || visit.status === "COMPLETED"
          ? await getPrescription(visit.id).catch(() => null)
          : null;

      setSelectedVisit(visit);
      setSelectedPatient(patient);
      setImages(visitImages);
      setSelectedImageIds(visitImages.map((image) => image.imageId));
      setAnalysis(latestAnalysis);
      setPrescription(savedPrescription);

      if (savedPrescription) {
        setSelectedDisease({
          id: savedPrescription.kcdDiseaseId,
          code: savedPrescription.kcdCode,
          nameKr: savedPrescription.kcdNameKr,
        });
        setDoctorNotes(savedPrescription.doctorNotes ?? "");
        setRevisitRecommendedDate(savedPrescription.revisitRecommendedDate ?? "");
        setPrescriptionDetails(savedPrescription.details.map((detail) => ({
          localId: String(detail.detailId),
          drugId: detail.drugId,
          medicineName: detail.medicineName,
          dosage: detail.dosage,
          durationDays: detail.durationDays,
          notes: detail.notes,
        })));
      }
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
      setPrescription(null);
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

  async function handleSearchDiseases() {
    setIsActionLoading(true);
    setErrorMessage(null);

    try {
      const result = await searchKcdDiseases(diseaseQuery, 8);
      setDiseaseResults(result.content);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  async function handleSearchDrugs() {
    setIsActionLoading(true);
    setErrorMessage(null);

    try {
      const result = await searchDrugs(drugQuery, 8);
      setDrugResults(result.content);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  async function handleDiagnoseVisit() {
    if (!selectedVisit || !selectedDisease) return;

    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const updatedVisit = await diagnoseVisit(selectedVisit.id);
      setSelectedVisit(updatedVisit);
      await loadVisitLists();
      setMessage("진단을 확정했습니다. 처방을 저장할 수 있습니다.");
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  async function handleSavePrescription() {
    if (!selectedVisit || !selectedDisease || prescriptionDetails.length === 0) return;

    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const savedPrescription = await savePrescription(selectedVisit.id, {
        kcdDiseaseId: selectedDisease.id,
        analysisId: analysis?.analysisId ?? null,
        revisitRecommendedDate: revisitRecommendedDate || null,
        doctorNotes: doctorNotes || null,
        details: prescriptionDetails.map((detail) => ({
          drugId: detail.drugId,
          medicineName: detail.medicineName,
          dosage: detail.dosage || null,
          durationDays: detail.durationDays ?? null,
          notes: detail.notes || null,
        })),
      });
      const updatedVisit = await getVisit(selectedVisit.id);
      setPrescription(savedPrescription);
      setSelectedVisit(updatedVisit);
      await loadVisitLists();
      setMessage("처방을 저장했습니다. 진료 완료 처리를 할 수 있습니다.");
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  async function handleCompleteVisit() {
    if (!selectedVisit) return;

    setIsActionLoading(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const updatedVisit = await completeVisit(selectedVisit.id);
      setSelectedVisit(updatedVisit);
      await loadVisitLists();
      setActiveTab("완료");
      setMessage("진료를 완료했습니다.");
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  function addDrugToPrescription(drug: DrugMaster) {
    setPrescriptionDetails((current) => [
      ...current,
      {
        localId: `${drug.id}-${Date.now()}`,
        drugId: drug.id,
        medicineName: drug.nameKr,
        dosage: "",
        durationDays: null,
        notes: "",
      },
    ]);
    setDrugQuery("");
    setDrugResults([]);
  }

  function updatePrescriptionDetail(
    localId: string,
    field: keyof Omit<PrescriptionDraftDetail, "localId" | "drugId">,
    value: string
  ) {
    setPrescriptionDetails((current) =>
      current.map((detail) => {
        if (detail.localId !== localId) return detail;

        if (field === "durationDays") {
          return { ...detail, durationDays: value ? Number(value) : null };
        }

        return { ...detail, [field]: value };
      })
    );
  }

  function removePrescriptionDetail(localId: string) {
    setPrescriptionDetails((current) => current.filter((detail) => detail.localId !== localId));
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
              <span className="text-[11px] text-green-400 font-medium">
                {selectedVisit ? STATUS_LABELS[selectedVisit.status] : "접수 선택"}
              </span>
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

          <Card title="진단 및 처방">
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <Input
                    label="상병검색"
                    placeholder="상병코드 또는 상병명"
                    value={diseaseQuery}
                    onChange={setDiseaseQuery}
                    disabled={!selectedVisit || isActionLoading}
                    className="text-xs"
                  />
                  <Button
                    onClick={handleSearchDiseases}
                    disabled={!selectedVisit || isActionLoading}
                    className="shrink-0 py-1.5 text-xs"
                  >
                    검색
                  </Button>
                </div>
                {selectedDisease && (
                  <p className="rounded border border-green-500/30 bg-green-500/10 px-3 py-2 text-xs text-green-100">
                    선택 상병: {selectedDisease.code} · {selectedDisease.nameKr}
                  </p>
                )}
                {diseaseResults.length > 0 && (
                  <div className="max-h-32 overflow-y-auto rounded border border-gray-700">
                    {diseaseResults.map((disease) => (
                      <button
                        key={disease.id}
                        onClick={() => {
                          setSelectedDisease(disease);
                          setDiseaseResults([]);
                          setDiseaseQuery(`${disease.code} ${disease.nameKr}`);
                        }}
                        className="block w-full border-b border-gray-800 px-3 py-2 text-left text-xs text-gray-100 hover:bg-gray-800"
                      >
                        {disease.code} · {disease.nameKr}
                      </button>
                    ))}
                  </div>
                )}
                <Button
                  onClick={handleDiagnoseVisit}
                  disabled={!canDiagnose || !selectedDisease || isActionLoading}
                  className="self-end py-1.5 text-xs"
                >
                  진단 확정
                </Button>
              </div>

              <div className="border-t border-gray-700 pt-3">
                <div className="flex items-center gap-2">
                  <Input
                    label="약품검색"
                    placeholder="처방코드 또는 약품명"
                    value={drugQuery}
                    onChange={setDrugQuery}
                    disabled={!selectedVisit || isActionLoading}
                    className="text-xs"
                  />
                  <Button
                    onClick={handleSearchDrugs}
                    disabled={!selectedVisit || isActionLoading}
                    className="shrink-0 py-1.5 text-xs"
                  >
                    검색
                  </Button>
                </div>
                {drugResults.length > 0 && (
                  <div className="mt-2 max-h-32 overflow-y-auto rounded border border-gray-700">
                    {drugResults.map((drug) => (
                      <button
                        key={drug.id}
                        onClick={() => addDrugToPrescription(drug)}
                        className="block w-full border-b border-gray-800 px-3 py-2 text-left text-xs text-gray-100 hover:bg-gray-800"
                      >
                        {drug.code} · {drug.nameKr}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              <div className="flex flex-col gap-2">
                {prescriptionDetails.length === 0 ? (
                  <p className="text-xs text-gray-400">약품을 검색해서 처방 목록에 추가하세요.</p>
                ) : (
                  prescriptionDetails.map((detail) => (
                    <div key={detail.localId} className="rounded border border-gray-700 p-2">
                      <div className="flex items-center gap-2">
                        <span className="flex-1 text-xs font-medium text-white">{detail.medicineName}</span>
                        <button
                          onClick={() => removePrescriptionDetail(detail.localId)}
                          className="rounded bg-gray-800 px-2 py-1 text-[10px] text-gray-300 hover:bg-gray-700"
                        >
                          삭제
                        </button>
                      </div>
                      <div className="mt-2 grid grid-cols-3 gap-2">
                        <input
                          value={detail.dosage ?? ""}
                          onChange={(event) => updatePrescriptionDetail(detail.localId, "dosage", event.target.value)}
                          placeholder="용법"
                          className="rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-white outline-none"
                        />
                        <input
                          type="number"
                          value={detail.durationDays ?? ""}
                          onChange={(event) => updatePrescriptionDetail(detail.localId, "durationDays", event.target.value)}
                          placeholder="일수"
                          className="rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-white outline-none"
                        />
                        <input
                          value={detail.notes ?? ""}
                          onChange={(event) => updatePrescriptionDetail(detail.localId, "notes", event.target.value)}
                          placeholder="주의사항"
                          className="rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-white outline-none"
                        />
                      </div>
                    </div>
                  ))
                )}
              </div>

              <div className="grid grid-cols-2 gap-2">
                <input
                  type="date"
                  value={revisitRecommendedDate}
                  onChange={(event) => setRevisitRecommendedDate(event.target.value)}
                  className="rounded border border-gray-600 bg-side-bg px-3 py-2 text-xs text-white outline-none"
                />
                <input
                  value={doctorNotes}
                  onChange={(event) => setDoctorNotes(event.target.value)}
                  placeholder="의사 소견"
                  className="rounded border border-gray-600 bg-side-bg px-3 py-2 text-xs text-white outline-none"
                />
              </div>

              {prescription && (
                <p className="rounded border border-blue-500/30 bg-blue-500/10 px-3 py-2 text-xs text-blue-100">
                  저장된 처방: {prescription.kcdCode} · 약품 {prescription.details.length}개
                </p>
              )}

              <div className="flex justify-end gap-2">
                <Button
                  onClick={handleSavePrescription}
                  disabled={!canSavePrescription || !selectedDisease || prescriptionDetails.length === 0 || isActionLoading}
                  className="py-1.5 text-xs"
                >
                  처방 저장
                </Button>
                <Button
                  onClick={handleCompleteVisit}
                  disabled={!canCompleteVisit || isActionLoading}
                  className="py-1.5 text-xs"
                >
                  진료 완료
                </Button>
              </div>
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
