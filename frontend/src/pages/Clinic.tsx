import { useEffect, useMemo, useState } from "react";
import { getLatestAnalysis, requestAnalysis, type AnalysisResponse } from "../api/analysis";
import { getPreliminaryAnalysis, type PreliminaryAnalysis } from "../api/kiosk";
import { listVisitImages, uploadVisitImage, type VisitImage } from "../api/images";
import { getPatient, type Patient } from "../api/patients";
import { getAiPrescriptionComment, getPrescription, savePrescription, type PrescriptionResponse } from "../api/prescription";
import { AuthedImage } from "../components/AuthedImage";
import { useAuth } from "../components/AuthContext";
import { searchDrugs, searchKcdDiseases } from "../api/reference";
import { completeVisit, getVisit, listVisits, startVisit, type Visit, type VisitStatus } from "../api/visits";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { ClinicPatientLookupPanel } from "../components/ClinicPatientLookupPanel";
import { SearchModal, type SearchItem } from "../components/SearchModal";
import { Table } from "../components/Table";

const DISEASE_TO_KCD_CODE: Record<string, string> = {
  nv:    'D22',
  mel:   'C43',
  bkl:   'L82',
  bcc:   'C44',
  akiec: 'L57.0',
  df:    'D23',
  vasc:  'D18',
};

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

const STATUS_COLORS: Record<VisitStatus, string> = {
  RECEIVED: "bg-orange-500/20 text-orange-300",
  IN_PROGRESS: "bg-blue-500/20 text-blue-300",
  ANALYZING: "bg-yellow-500/20 text-yellow-300",
  ANALYZED: "bg-purple-500/20 text-purple-300",
  DIAGNOSED: "bg-indigo-500/20 text-indigo-300",
  PRESCRIBED: "bg-green-500/20 text-green-300",
  COMPLETED: "bg-gray-500/20 text-gray-400",
  CANCELLED: "bg-red-500/20 text-red-300",
};

// 대기 탭: 처방 전 / 완료 탭: 처방완료(PRESCRIBED) 이후
const WAITING_STATUSES: VisitStatus[] = ["RECEIVED", "IN_PROGRESS", "ANALYZING", "ANALYZED", "DIAGNOSED"];
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
  const { user } = useAuth();
  const [activeTab, setActiveTab] = useState<"대기" | "완료">("대기");
  const [waitingVisits, setWaiting] = useState<Visit[]>([]);
  const [completedVisits, setCompleted] = useState<Visit[]>([]);
  const [patientNameMap, setNameMap] = useState<Map<number, string>>(new Map());
  const [selectedVisit, setSelectedVisit] = useState<Visit | null>(null);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [images, setImages] = useState<VisitImage[]>([]);
  const [selectedImageIds, setSelectedImageIds] = useState<number[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<AnalysisResponse | null>(null);
  const [preliminaryAnalysis, setPreliminaryAnalysis] = useState<PreliminaryAnalysis | null>(null);
  const [prescription, setPrescription] = useState<PrescriptionResponse | null>(null);

  // 분석 결과 상세 토글
  const [expandedReason, setExpandedReason] = useState<number | null>(null);
  // 이미지 뷰 모드: none → original → heatmap → original ...
  const [imageViewMode, setImageViewMode] = useState<"none" | "original" | "heatmap">("none");

  // 처방 폼
  type AnalysisCandidate = AnalysisResponse["top5"][number];
  type SelectedKcd = { id: number; code: string; nameKr: string; isPrimary: boolean; sourceDiseaseCode?: string };
  const [selectedKcds, setSelectedKcds] = useState<SelectedKcd[]>([]);
  const [selectedDrug, setSelectedDrug] = useState<SearchItem | null>(null);
  const [drugDosage, setDrugDosage] = useState("");
  const [drugDays, setDrugDays] = useState("");
  const [doctorNotes, setDoctorNotes] = useState("");
  const [selectedAnalysisCandidateCodes, setSelectedAnalysisCandidateCodes] = useState<string[]>([]);
  const [resolvingCandidateCode, setResolvingCandidateCode] = useState<string | null>(null);

  // 모달
  const [isKcdModalOpen, setKcdModalOpen] = useState(false);
  const [isDrugModalOpen, setDrugModalOpen] = useState(false);

  // AI 처방 코멘트 (line1·line2 + 메타)
  const [aiComment, setAiComment] = useState<{
    line1: string;
    line2: string;
    model: string;
    generatedAt: string; // ISO 8601 — 프론트 수신 시점
    edited: boolean;     // 의사가 저장 전 수정했으면 true
  } | null>(null);
  const [isCommentLoading, setIsCommentLoading] = useState(false);

  // 로딩 / 메시지
  const [isLoading, setIsLoading] = useState(false);
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const canUploadImage = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  const canAnalyze = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED";
  // 처방 폼: 진료중(IN_PROGRESS) 이후 상태에서 표시
  const canPrescribe = selectedVisit?.status === "IN_PROGRESS" || selectedVisit?.status === "ANALYZED" || selectedVisit?.status === "DIAGNOSED";

  // ── 목록 로드 ────────────────────────────────────────────────────
  async function loadVisitLists() {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const allGroups = await Promise.all([
        ...WAITING_STATUSES.map((s) => listVisits(s)),
        ...COMPLETED_STATUSES.map((s) => listVisits(s)),
      ]);
      const waiting = allGroups.slice(0, WAITING_STATUSES.length).flat();
      const completed = allGroups.slice(WAITING_STATUSES.length).flat();
      setWaiting(waiting);
      setCompleted(completed);

      // 이름 일괄 조회
      const allVisits = [...waiting, ...completed];
      const uniqueIds = [...new Set(allVisits.map((v) => v.patientId))];
      const patients = await Promise.all(uniqueIds.map((id) => getPatient(id).catch(() => null)));
      const nameMap = new Map<number, string>();
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
    setPreliminaryAnalysis(null);
    setSelectedKcds([]);
    setSelectedAnalysisCandidateCodes([]);
    setAiComment(null);
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
      const preliminary = await getPreliminaryAnalysis(visit.id).catch(() => null);

      setSelectedVisit(visit);
      setSelectedPatient(patient);
      setImages(visitImages);
      setSelectedImageIds(visitImages.map((img) => img.imageId));
      setAnalysis(latestAnalysis);
      setPrescription(latestPrescription);
      setPreliminaryAnalysis(preliminary);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsActionLoading(false);
    }
  }

  useEffect(() => { loadVisitLists(); }, []);

  useEffect(() => { setImageViewMode("none"); }, [analysis]);

  useEffect(() => {
    if (!selectedFile) { setPreviewUrl(null); return; }
    const url = URL.createObjectURL(selectedFile);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [selectedFile]);

  // ── 테이블 데이터 ─────────────────────────────────────────────────
  const activeVisits = activeTab === "대기" ? waitingVisits : completedVisits;

  const currentPatientInfo = useMemo(() => [
    ["이름", selectedPatient?.name ? <span key="n" className="font-semibold">{selectedPatient.name}</span> : "-"],
    ["환자번호", selectedPatient ? `P${String(selectedPatient.id).padStart(5, "0")}` : "-"],
    ["나이", calculateAge(selectedPatient?.birthDate)],
    ["성별", formatGender(selectedPatient?.gender)],
    ["연락처", selectedPatient?.phone ?? "-"],
    ["메모", selectedPatient?.memo ?? "-"],
    ["접수번호", selectedVisit ? `V${String(selectedVisit.id).padStart(5, "0")}` : "-"],
    ["현재상태", selectedVisit ? STATUS_LABELS[selectedVisit.status] : "-"],
  ], [selectedPatient, selectedVisit]);

  const centerTableData = activeVisits.map((visit, idx) => [
    idx + 1,
    `V${String(visit.id).padStart(5, "0")}`,
    patientNameMap.get(visit.patientId) ?? "-",
    formatDateTime(visit.visitDate),
    <span key={`st-${visit.id}`} className={`px-2 py-0.5 rounded text-[10px] whitespace-nowrap ${STATUS_COLORS[visit.status]}`}>
      {STATUS_LABELS[visit.status]}
    </span>,
  ]);

  // ── 핸들러 ───────────────────────────────────────────────────────
  async function handleStartVisit(targetVisit = selectedVisit) {
    if (!targetVisit) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      const v = await startVisit(targetVisit.id);
      await loadVisitLists();
      await loadVisitDetail(v.id);
      setMessage("진료를 시작했습니다. 이미지를 업로드하세요.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  async function handleVisitRowDoubleClick(visit: Visit) {
    if (visit.status === "RECEIVED") {
      await handleStartVisit(visit);
      return;
    }
    await loadVisitDetail(visit.id);
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
      setSelectedAnalysisCandidateCodes([]);
      setSelectedKcds([]);
      setSelectedVisit((cur) => cur ? { ...cur, status: "ANALYZED" } : cur);
      await loadVisitLists();
      setMessage("AI 분석이 완료되었습니다. 결과를 확인하고 처방을 입력하세요.");
    } catch (e) { setErrorMessage(getErrorMessage(e)); }
    finally { setIsActionLoading(false); }
  }

  async function handleSavePrescription() {
    if (!selectedVisit || selectedKcds.length === 0 || !selectedDrug || !user) return;
    setIsActionLoading(true); setErrorMessage(null); setMessage(null);
    try {
      // 진단 확정 → 처방 저장 전이는 백엔드가 한 트랜잭션에서 처리한다(Visit.confirmDiagnosisAndPrescribe).
      // 진료중(AI 분석 없이 바로 처방)·분석완료 어느 상태에서 들어와도 여기서는 저장만 호출하면 된다.
      const visit = selectedVisit;

      // 작성자는 보내지 않는다 — 서버가 JWT에서 결정한다.
      const saved = await savePrescription(visit.id, {
        diseases: selectedKcds.map(k => ({ kcdDiseaseId: k.id, isPrimary: k.isPrimary })),
        analysisId: analysis?.analysisId ?? null,
        doctorNotes: doctorNotes.trim() || null,
        aiComment: aiComment ? `${aiComment.line1}\n${aiComment.line2}`.trim() : null,
        aiCommentModel: aiComment?.model ?? null,
        aiCommentGeneratedAt: aiComment?.generatedAt ?? null,
        aiCommentEdited: aiComment?.edited ?? null,
        details: [{
          medicineName: selectedDrug.nameKr,
          dosage: drugDosage.trim() || null,
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

  function setPrimaryKcd(kcdId: number) {
    setSelectedKcds((prev) =>
      prev.map((item) => ({ ...item, isPrimary: item.id === kcdId }))
    );
  }

  function removeSelectedKcd(kcdId: number) {
    setSelectedKcds((prev) => {
      const removed = prev.find((item) => item.id === kcdId);
      const next = prev.filter((item) => item.id !== kcdId);
      if (removed?.isPrimary && next.length > 0) {
        return next.map((item, index) => ({ ...item, isPrimary: index === 0 }));
      }
      return next;
    });
  }

  async function handleToggleAnalysisCandidate(item: AnalysisCandidate) {
    const isSelectedCandidate = selectedAnalysisCandidateCodes.includes(item.diseaseCode);
    if (isSelectedCandidate) {
      setSelectedAnalysisCandidateCodes((prev) => prev.filter((code) => code !== item.diseaseCode));
      setSelectedKcds((prev) => {
        const next = prev.filter((kcd) => kcd.sourceDiseaseCode !== item.diseaseCode);
        return next.some((kcd) => kcd.isPrimary) || next.length === 0
          ? next
          : next.map((kcd, index) => ({ ...kcd, isPrimary: index === 0 }));
      });
      return;
    }

    setSelectedAnalysisCandidateCodes((prev) => [...prev, item.diseaseCode]);
    setResolvingCandidateCode(item.diseaseCode);
    setErrorMessage(null);
    try {
      const result = await searchKcdDiseases(item.diseaseNameKo, 10);
      const matched = result.content.find((disease) => disease.nameKr === item.diseaseNameKo)
        ?? result.content.find((disease) => disease.nameKr.includes(item.diseaseNameKo) || item.diseaseNameKo.includes(disease.nameKr))
        ?? result.content[0];

      if (!matched) {
        const fallbackCode = DISEASE_TO_KCD_CODE[item.diseaseCode];
        if (fallbackCode) {
          const fallbackResult = await searchKcdDiseases(fallbackCode, 5);
          const fallbackMatched = fallbackResult.content.find(d => d.code === fallbackCode)
            ?? fallbackResult.content[0];
          if (fallbackMatched) {
            setSelectedKcds((prev) => {
              if (prev.some((kcd) => kcd.id === fallbackMatched.id)) {
                return prev.map((kcd) =>
                  kcd.id === fallbackMatched.id
                    ? { ...kcd, sourceDiseaseCode: item.diseaseCode }
                    : kcd
                );
              }
              const isFirst = prev.length === 0;
              return [...prev, {
                id: fallbackMatched.id,
                code: fallbackMatched.code,
                nameKr: fallbackMatched.nameKr,
                isPrimary: isFirst,
                sourceDiseaseCode: item.diseaseCode,
              }];
            });
            return;
          }
        }
        setMessage(`${item.diseaseNameKo} 후보를 선택했습니다. 매칭되는 KCD 상병코드는 상병코드 검색으로 직접 추가해 주세요.`);
        return;
      }

      setSelectedKcds((prev) => {
        if (prev.some((kcd) => kcd.id === matched.id)) {
          return prev.map((kcd) =>
            kcd.id === matched.id
              ? { ...kcd, sourceDiseaseCode: item.diseaseCode }
              : kcd
          );
        }
        const isFirst = prev.length === 0;
        return [
          ...prev,
          {
            id: matched.id,
            code: matched.code,
            nameKr: matched.nameKr,
            isPrimary: isFirst,
            sourceDiseaseCode: item.diseaseCode,
          },
        ];
      });
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setResolvingCandidateCode(null);
    }
  }

  const status = selectedVisit?.status;

  return (
    <>
      {/* KCD 상병코드 모달 */}
      <SearchModal
        isOpen={isKcdModalOpen}
        onClose={() => setKcdModalOpen(false)}
        onSelect={(item) => {
          setSelectedKcds(prev => {
            if (prev.find(k => k.id === item.id)) return prev;
            const isFirst = prev.length === 0;
            return [...prev, { id: item.id, code: item.code, nameKr: item.nameKr, isPrimary: isFirst }];
          });
        }}
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

      <div className="flex-1 min-h-0 p-[6px] flex gap-[6px] overflow-hidden">

        {/* Left Column */}
        <section className="w-[340px] flex shrink-0 flex-col gap-[6px] overflow-hidden">
          <ClinicPatientLookupPanel
            selectedPatientId={selectedPatient?.id}
            selectedVisitId={selectedVisit?.id}
            onSelectVisit={(visitId) => void loadVisitDetail(visitId)}
          />

          <Card title="선택 환자 정보" className="min-h-0" contentClassName="!p-2 [&_td]:!px-2 [&_td]:!py-1.5 [&_th]:!px-2 [&_th]:!py-1.5">
            {selectedPatient ? (
              <Table headers={["항목", "내용"]} data={currentPatientInfo} />
            ) : (
              <p className="py-4 text-center text-xs text-gray-400">
                조회 결과나 진료 현황에서 내원을 선택하면 환자 정보가 표시됩니다.
              </p>
            )}
          </Card>

          {preliminaryAnalysis && (
            <Card title="대기 중 예비 분석결과" className="min-h-0" contentClassName="!p-2">
              <div className="flex flex-col gap-2">
                <p className="rounded border border-yellow-500/40 bg-yellow-500/10 px-2 py-1.5 text-[11px] text-yellow-200">
                  ⚠️ 본 분석은 AI 보조 참고용이며 의학적 진단이 아닙니다. 결과가 정확하지 않을 수 있으며, 반드시 진료실에서 의사의 확인 진료와 처방을 받으셔야 합니다.
                </p>

                {preliminaryAnalysis.topK[0] && (
                  <div className="rounded border border-blue-500/30 bg-blue-500/10 p-2">
                    <p className="text-xs text-gray-300">Top 1 (키오스크 예비분석)</p>
                    <p className="mt-0.5 text-sm font-semibold text-white">
                      {preliminaryAnalysis.topK[0].diseaseNameKo} ({preliminaryAnalysis.topK[0].diseaseCode})
                    </p>
                    <p className="mt-0.5 text-xs text-blue-100">
                      신뢰도 {(preliminaryAnalysis.topK[0].confidence * 100).toFixed(1)}%
                    </p>
                  </div>
                )}

                {preliminaryAnalysis.gradcamUrl && (
                  <div className="flex flex-col gap-1">
                    <span className="text-[11px] text-gray-400">AI 병변 분석 히트맵</span>
                    <div className="rounded border border-gray-700 bg-gray-900 overflow-hidden">
                      <AuthedImage
                        src={preliminaryAnalysis.gradcamUrl}
                        alt="키오스크 예비분석 GradCAM"
                        className="w-full"
                      />
                    </div>
                  </div>
                )}

                <div className="border border-gray-700 rounded overflow-hidden">
                  <div className="grid grid-cols-[34px_72px_1fr_64px] bg-gray-950 px-2 py-1.5 text-[10px] font-semibold text-gray-400">
                    <span>순위</span><span>상병코드</span><span>상병명</span><span className="text-right">신뢰도</span>
                  </div>
                  {preliminaryAnalysis.topK.map((item, idx) => (
                    <div key={item.diseaseCode} className="grid grid-cols-[34px_72px_1fr_64px] items-center border-t border-gray-800 px-2 py-1.5 text-xs">
                      <span className="text-gray-400">{idx + 1}</span>
                      <span className="font-mono text-blue-300">{item.diseaseCode}</span>
                      <span className="text-white">{item.diseaseNameKo}</span>
                      <span className="text-right text-gray-200">{(item.confidence * 100).toFixed(1)}%</span>
                    </div>
                  ))}
                </div>

                {preliminaryAnalysis.aiComment && (
                  <p className="text-xs text-gray-300 leading-relaxed">{preliminaryAnalysis.aiComment}</p>
                )}

                <p className="text-[10px] text-gray-400">{formatDateTime(preliminaryAnalysis.analyzedAt)}</p>
              </div>
            </Card>
          )}
        </section>

        {/* Center Column */}
        <section className="flex-1 min-w-0 flex flex-col gap-[6px] overflow-hidden">
          <Card title="진료 현황" className="shrink-0" contentClassName="!p-2 [&_td]:!px-3 [&_td]:!py-1.5 [&_th]:!px-3 [&_th]:!py-1.5">
            <div className="flex border-b border-gray-700 mb-2 pb-1 gap-2">
              {(["대기", "완료"] as const).map((tab) => (
                <button
                  key={tab}
                  onClick={() => setActiveTab(tab)}
                  className={`px-3 py-1 text-xs font-medium rounded transition-colors cursor-pointer ${activeTab === tab ? "bg-blue-600 text-white font-bold" : "bg-gray-800 text-gray-400 hover:bg-gray-700"
                    }`}
                >
                  {tab === "대기" ? "진료대기" : "진료완료"}
                </button>
              ))}
            </div>

            {errorMessage && (
              <p className="mb-2 rounded border border-red-500/40 bg-red-500/10 px-3 py-1.5 text-xs text-red-200">{errorMessage}</p>
            )}
            {message && (
              <p className="mb-2 rounded border border-blue-500/40 bg-blue-500/10 px-3 py-1.5 text-xs text-blue-100">{message}</p>
            )}
            {isLoading && <p className="mb-2 text-xs text-gray-400">목록 로딩 중...</p>}

            <Table
              headers={["순번", "접수번호", "이름", "접수시간", "현재상태"]}
              data={centerTableData}
              getRowProps={(_, rowIdx) => {
                const visit = activeVisits[rowIdx];
                const isSelected = selectedVisit?.id === visit.id;
                const canStartByDoubleClick = visit.status === "RECEIVED";

                return {
                  onClick: () => loadVisitDetail(visit.id),
                  onDoubleClick: () => handleVisitRowDoubleClick(visit),
                  title: canStartByDoubleClick ? "더블클릭하면 진료를 시작합니다." : "클릭하면 진료 상세를 확인합니다.",
                  className: `cursor-pointer ${isSelected
                      ? "bg-blue-500/10 outline outline-1 outline-blue-500/40"
                      : canStartByDoubleClick
                        ? "hover:bg-blue-500/10"
                        : ""
                    }`,
                };
              }}
            />
          </Card>

          <Card title="선택 내원 이미지 및 업로드" className="min-h-0 flex-1" contentClassName="!p-2">
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2 flex-wrap">
                <label className={`px-3 py-2 rounded-lg text-xs transition-colors ${canUploadImage ? "bg-blue-600 hover:bg-blue-500 cursor-pointer" : "bg-gray-700 text-gray-400 cursor-not-allowed"
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
                <Button onClick={handleUploadImage} disabled={!selectedFile || !canUploadImage || isActionLoading} className="py-1 text-xs">
                  업로드
                </Button>
                <Button onClick={handleAnalyze} disabled={!canAnalyze || selectedImageIds.length === 0 || isActionLoading} className="py-1 text-xs">
                  {isActionLoading ? "분석 중..." : "AI 분석"}
                </Button>
              </div>

              {!selectedVisit && (
                <p className="rounded border border-gray-700 bg-gray-900/40 px-3 py-4 text-center text-xs text-gray-400">
                  왼쪽 조회 패널 또는 진료 현황에서 내원을 선택하면 이미지 업로드와 AI 분석을 진행할 수 있습니다.
                </p>
              )}
              {status === "RECEIVED" && (
                <div className="flex items-center gap-3 rounded border border-orange-500/30 bg-orange-500/10 px-3 py-2">
                  <p className="flex-1 text-[11px] text-orange-100">
                    이미지 업로드 전 진료 현황의 접수 행을 더블클릭해 진료를 시작해야 합니다.
                  </p>
                </div>
              )}

              {previewUrl && (
                <div className="w-full max-w-[360px] overflow-hidden rounded border border-gray-700 bg-gray-900">
                  <img src={previewUrl} alt="미리보기" className="h-40 w-full object-contain" />
                </div>
              )}

              {selectedVisit && images.length === 0 ? (
                <p className="rounded border border-gray-700 bg-gray-900/40 px-3 py-4 text-center text-xs text-gray-400">
                  선택한 내원에 등록된 이미지가 없습니다.
                </p>
              ) : (
                <div className="grid grid-cols-2 gap-2 lg:grid-cols-3">
                  {images.map((image) => {
                    const selected = selectedImageIds.includes(image.imageId);
                    return (
                      <button
                        key={image.imageId}
                        onClick={() => toggleSelectedImage(image.imageId)}
                        className={`overflow-hidden rounded border text-left transition-colors ${selected ? "border-blue-500 bg-blue-500/10" : "border-gray-700 bg-gray-900"
                          }`}
                      >
                        <AuthedImage
                          src={image.imageUrl}
                          alt={`이미지 ${image.imageId}`}
                          className="h-20 w-full object-cover"
                          fallback={<div className="h-20 w-full bg-gray-800" />}
                        />
                        <div className="px-2 py-1 text-[10px] text-gray-300">
                          #{image.imageId} · {formatDateTime(image.uploadedAt)}
                        </div>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          </Card>
        </section>

        {/* Right Column */}
        <section className="flex-1 min-w-0 flex flex-col gap-[6px] overflow-hidden">
          {/* AI 분석 결과 */}
          <Card title="AI 분석 결과" className="shrink-0" contentClassName="!p-2">
            <div className="flex flex-col gap-2">
              {!analysis ? (
                <p className="text-xs text-gray-300">이미지를 선택하고 AI 분석을 요청하면 결과가 표시됩니다.</p>
              ) : (
                <>
                  <div className="rounded border border-blue-500/30 bg-blue-500/10 p-2">
                    <p className="text-xs text-gray-300">Top 1</p>
                    <p className="mt-0.5 text-sm font-semibold text-white">
                      {analysis.top1.diseaseNameKo} ({analysis.top1.diseaseCode})
                    </p>
                    <p className="mt-0.5 text-xs text-blue-100">
                      신뢰도 {(Number(analysis.top1.confidence) * 100).toFixed(1)}%
                    </p>
                  </div>

                  {/* 분석 이미지 / 히트맵 토글
                      핵심: <img> 태그 하나만 두고 src만 교체 → 컨테이너 크기 고정, 커튼 효과 */}
                  {(() => {
                    const analyzedImageUrl =
                      images.find((img) => img.imageId === selectedImageIds[0])?.imageUrl
                      ?? images[0]?.imageUrl;
                    const hasHeatmap = !!analysis.heatmapImageUrl;

                    const buttonLabel =
                      imageViewMode === "none" ? "분석 이미지" :
                        imageViewMode === "original" ? (hasHeatmap ? "히트맵 보기" : "이미지 닫기") :
                          "원본 이미지";

                    const buttonColor =
                      imageViewMode === "heatmap"
                        ? "bg-orange-600 hover:bg-orange-500 text-white"
                        : "bg-gray-700 hover:bg-gray-600 text-gray-200";

                    const handleClick = () => {
                      if (imageViewMode === "none") setImageViewMode("original");
                      else if (imageViewMode === "original") setImageViewMode(hasHeatmap ? "heatmap" : "none");
                      else setImageViewMode("original");
                    };

                    // src만 바뀌고 <img> 태그는 유지 → 동일 위치·동일 크기 보장
                    const displayUrl =
                      imageViewMode === "heatmap" ? analysis.heatmapImageUrl! : analyzedImageUrl;

                    return (
                      <div className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] text-gray-400">
                            {imageViewMode === "heatmap" ? "AI 병변 분석 히트맵" : "분석 이미지"}
                          </span>
                          <button
                            onClick={handleClick}
                            className={`px-3 py-1 rounded text-[11px] font-medium transition-colors ${buttonColor}`}
                          >
                            {buttonLabel}
                          </button>
                        </div>

                        {/* 이미지 컨테이너: imageViewMode가 none이어도 DOM에 존재 유지
                            visibility로 숨겨서 크기가 절대 바뀌지 않음 */}
                        <div
                          className="rounded border border-gray-700 bg-gray-900 overflow-hidden"
                          style={{ visibility: imageViewMode === "none" ? "hidden" : "visible", height: imageViewMode === "none" ? 0 : undefined }}
                        >
                          {displayUrl && (
                            <>
                              <AuthedImage
                                src={displayUrl}
                                alt={imageViewMode === "heatmap" ? "GradCAM 히트맵" : "분석 이미지"}
                                className="w-full"
                                style={{ display: "block", transition: "opacity 0.15s ease" }}
                              />
                              {imageViewMode === "heatmap" && (
                                <p className="px-2 py-1 text-[10px] text-gray-400">
                                  🔴 빨간색 — AI가 진단 근거로 삼은 병변 부위 · GradCAM 기반
                                </p>
                              )}
                            </>
                          )}
                        </div>
                      </div>
                    );
                  })()}
                  <div className="border border-gray-700 rounded overflow-hidden">
                    <div className="grid grid-cols-[32px_34px_72px_1fr_64px_44px] bg-gray-950 px-2 py-1.5 text-[10px] font-semibold text-gray-400">
                      <span>선택</span><span>순위</span><span>상병코드</span><span>상병명</span><span className="text-right">신뢰도</span><span></span>
                    </div>
                    {analysis.top5.map((item) => {
                      const isCandidateSelected = selectedAnalysisCandidateCodes.includes(item.diseaseCode);
                      const isResolvingCandidate = resolvingCandidateCode === item.diseaseCode;

                      return (
                        <div key={item.rank} className="border-t border-gray-800">
                          <div className="grid grid-cols-[32px_34px_72px_1fr_64px_44px] items-center px-2 py-1.5 text-xs">
                            <span>
                              <input
                                type="checkbox"
                                checked={isCandidateSelected}
                                disabled={isResolvingCandidate}
                                onChange={() => void handleToggleAnalysisCandidate(item)}
                                aria-label={`${item.diseaseNameKo} 후보 선택`}
                                className="accent-blue-500 disabled:cursor-wait"
                              />
                            </span>
                            <span className="text-gray-400">{item.rank}</span>
                            <span className="font-mono text-blue-300">{item.diseaseCode}</span>
                            <span className="text-white">{item.diseaseNameKo}</span>
                            <span className="text-right text-gray-200">
                              {isResolvingCandidate ? "검색 중" : `${(item.confidence * 100).toFixed(1)}%`}
                            </span>
                            <span className="text-right">
                              {item.reason && item.rank <= 2 && (
                                <button
                                  onClick={() => setExpandedReason(expandedReason === item.rank ? null : item.rank)}
                                  className="px-2 py-0.5 bg-blue-600 hover:bg-blue-500 text-white text-[10px] rounded transition-colors"
                                >
                                  {expandedReason === item.rank ? "닫기" : "상세"}
                                </button>
                              )}
                            </span>
                          </div>
                          {expandedReason === item.rank && item.reason && (
                            <div className="mx-3 mb-2 px-3 py-2 bg-gray-800 rounded text-[11px] text-gray-200 leading-relaxed relative">
                              <span className="absolute -top-1.5 right-6 text-gray-800 text-base">▲</span>
                              {item.reason}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                  <p className="text-[10px] text-gray-400">
                    모델 {analysis.modelVersion} · {analysis.inferenceTimeMs}ms · {formatDateTime(analysis.analyzedAt)}
                  </p>
                </>
              )}
            </div>
          </Card>

          {/* 처방 카드 — 진료중(IN_PROGRESS) 이후 바로 표시 */}
          {selectedVisit && ["IN_PROGRESS", "ANALYZED", "DIAGNOSED", "PRESCRIBED", "COMPLETED"].includes(selectedVisit.status) && (
            <Card title="처방" className="min-h-0 flex-1" contentClassName="!p-2">
              <div className="flex flex-col gap-2">

                {/* 처방 입력 폼 — ANALYZED 또는 DIAGNOSED */}
                {canPrescribe && (
                  <div className="flex flex-col gap-2">
                    {status === "DIAGNOSED" && (
                      <p className="text-[10px] text-indigo-400 font-medium">진단 확정됨</p>
                    )}

                    {/* KCD 상병코드 */}
                    <div className="flex flex-col gap-1">
                      <span className="text-[10px] text-gray-400">
                        KCD 상병코드 <span className="text-red-400">*</span>
                      </span>
                      <div className="overflow-hidden rounded border border-gray-700">
                        <div className="grid grid-cols-[52px_74px_1fr_48px] bg-gray-950 px-2 py-1.5 text-[10px] font-semibold text-gray-400">
                          <span>구분</span>
                          <span>코드</span>
                          <span>상병명</span>
                          <span className="text-right">관리</span>
                        </div>
                        {selectedKcds.length > 0 ? (
                          selectedKcds.map((k) => (
                            <div key={k.id} className="grid grid-cols-[52px_74px_1fr_48px] items-center border-t border-gray-800 px-2 py-1.5 text-xs">
                              <label className="flex items-center gap-1.5 text-[10px] text-gray-300">
                                <input
                                  type="radio"
                                  name="primaryKcd"
                                  checked={k.isPrimary}
                                  onChange={() => setPrimaryKcd(k.id)}
                                  className="accent-blue-500"
                                />
                                {k.isPrimary ? "주상병" : "부상병"}
                              </label>
                              <span className="font-mono text-blue-300">{k.code}</span>
                              <span className="min-w-0 truncate text-white" title={k.nameKr}>{k.nameKr}</span>
                              <button
                                onClick={() => removeSelectedKcd(k.id)}
                                className="text-right text-[10px] text-gray-400 transition-colors hover:text-red-400"
                              >
                                삭제
                              </button>
                            </div>
                          ))
                        ) : (
                          <div className="border-t border-gray-800 px-2 py-2 text-center text-[11px] text-gray-500">
                            선택된 상병코드가 없습니다.
                          </div>
                        )}
                      </div>
                      <button
                        onClick={() => setKcdModalOpen(true)}
                        className="w-full px-2 py-1.5 rounded border border-dashed border-gray-600 text-xs text-gray-400 hover:border-blue-500 hover:text-blue-300 transition-colors text-left"
                      >
                        + 상병코드 검색 (클릭하여 추가)
                      </button>
                    </div>

                    {/* AI 처방 코멘트 */}
                    <div className="flex flex-col gap-1.5">
                      <button
                        onClick={async () => {
                          if (!selectedVisit || selectedKcds.length === 0) return;
                          setIsCommentLoading(true);
                          try {
                            const result = await getAiPrescriptionComment(
                              selectedVisit.id,
                              selectedKcds.map(k => ({ kcdCode: k.code, kcdNameKr: k.nameKr, isPrimary: k.isPrimary })),
                              selectedVisit.receptionMemo
                            );
                            setAiComment({
                              ...result,
                              model: "gemini-3.1-flash-lite",
                              // LocalDateTime은 Z(timezone) 없는 ISO 형식만 허용
                              generatedAt: new Date().toISOString().slice(0, 19),
                              edited: false,
                            });
                          } catch { setAiComment(null); }
                          finally { setIsCommentLoading(false); }
                        }}
                        disabled={selectedKcds.length === 0 || isCommentLoading}
                        className="w-full px-3 py-1.5 rounded bg-indigo-600 hover:bg-indigo-500 text-xs text-white transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isCommentLoading ? "AI 분석 중..." : "AI 처방 코멘트 생성"}
                      </button>
                      {aiComment && (
                        <div className="rounded border border-indigo-500/30 bg-indigo-500/10 px-2 py-1.5 flex flex-col gap-1.5">
                          <div className="flex items-center justify-between">
                            <span className="text-[10px] text-indigo-400 font-medium">
                              AI 처방 코멘트{aiComment.edited && <span className="ml-1 text-yellow-400">(수정됨)</span>}
                            </span>
                            <span className="text-[10px] text-gray-500">{aiComment.model}</span>
                          </div>
                          <textarea
                            value={`${aiComment.line1}\n${aiComment.line2}`.trim()}
                            onChange={(e) => {
                              const [l1 = "", l2 = ""] = e.target.value.split("\n");
                              setAiComment((prev) => prev ? { ...prev, line1: l1, line2: l2, edited: true } : prev);
                            }}
                            rows={3}
                            className="w-full bg-indigo-950/40 border border-indigo-500/20 rounded px-2 py-1.5 text-[11px] text-indigo-100 resize-none focus:outline-none focus:border-indigo-400"
                          />
                          {!aiComment.line1 && !aiComment.line2 && (
                            <p className="text-[11px] text-red-300">응답을 받지 못했습니다. 백엔드 로그를 확인하세요.</p>
                          )}
                        </div>
                      )}
                    </div>

                    {/* 약품 */}
                    <div className="flex flex-col gap-1">
                      <span className="text-[10px] text-gray-400">
                        처방 약품 <span className="text-red-400">*</span>
                      </span>
                      <div className="overflow-hidden rounded border border-gray-700">
                        <div className="grid grid-cols-[72px_1fr_1fr_58px_44px] bg-gray-950 px-2 py-1.5 text-[10px] font-semibold text-gray-400">
                          <span>약품코드</span>
                          <span>약품명</span>
                          <span>용법</span>
                          <span>기간</span>
                          <span className="text-right">관리</span>
                        </div>
                        {selectedDrug ? (
                          <div className="grid grid-cols-[72px_1fr_1fr_58px_44px] items-center gap-1.5 border-t border-gray-800 px-2 py-1.5 text-xs">
                            <span className="font-mono text-blue-300">{selectedDrug.code}</span>
                            <span className="min-w-0 truncate text-white" title={selectedDrug.nameKr}>{selectedDrug.nameKr}</span>
                            <input
                              type="text"
                              value={drugDosage}
                              onChange={(e) => setDrugDosage(e.target.value)}
                              placeholder="1일 2회 도포"
                              className="min-w-0 rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-white focus:border-blue-500 focus:outline-none"
                            />
                            <input
                              type="number"
                              value={drugDays}
                              onChange={(e) => setDrugDays(e.target.value)}
                              placeholder="일"
                              className="w-full rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-white focus:border-blue-500 focus:outline-none"
                            />
                            <button
                              onClick={() => setSelectedDrug(null)}
                              className="text-right text-[10px] text-gray-400 transition-colors hover:text-red-400"
                            >
                              삭제
                            </button>
                          </div>
                        ) : (
                          <div className="border-t border-gray-800 px-2 py-2 text-center text-[11px] text-gray-500">
                            선택된 약품이 없습니다.
                          </div>
                        )}
                      </div>
                      <button
                        onClick={() => setDrugModalOpen(true)}
                        className="w-full px-2 py-1.5 rounded border border-dashed border-gray-600 text-xs text-gray-400 hover:border-blue-500 hover:text-blue-300 transition-colors text-left"
                      >
                        + 약품 검색 (클릭하여 선택)
                      </button>
                    </div>

                    {/* 의사 소견 */}
                    <div className="flex flex-col gap-1">
                      <span className="text-[10px] text-gray-400">의사 소견 (선택)</span>
                      <textarea
                        value={doctorNotes}
                        onChange={(e) => setDoctorNotes(e.target.value)}
                        placeholder="소견을 입력하세요"
                        rows={1}
                        className="px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-xs text-white focus:outline-none focus:border-blue-500 resize-none"
                      />
                    </div>

                    <Button
                      onClick={handleSavePrescription}
                      disabled={selectedKcds.length === 0 || !selectedDrug || isActionLoading}
                      className="py-1.5 text-xs w-full"
                    >
                      처방 저장
                    </Button>
                  </div>
                )}

                {/* PRESCRIBED: 처방 내용 + 진료 완료 */}
                {status === "PRESCRIBED" && prescription && (
                  <div className="flex flex-col gap-3">
                    <p className="text-[10px] text-blue-400 font-medium">처방 저장됨</p>
                    <PrescriptionSummary prescription={prescription} />
                    <Button onClick={handleComplete} disabled={isActionLoading} className="py-2 text-xs w-full">
                      진료 완료
                    </Button>
                  </div>
                )}

                {/* COMPLETED */}
                {status === "COMPLETED" && prescription && (
                  <div className="flex flex-col gap-3">
                    <p className="text-[10px] text-gray-400 font-medium">진료 완료</p>
                    <PrescriptionSummary prescription={prescription} />
                  </div>
                )}
              </div>
            </Card>
          )}

          {/* 진료정보 */}
          <Card title="진료정보" className="shrink-0" contentClassName="!p-2">
            <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
              <InfoItem label="접수번호" value={selectedVisit ? `V${String(selectedVisit.id).padStart(5, "0")}` : "-"} />
              <InfoItem label="환자번호" value={selectedPatient ? `P${String(selectedPatient.id).padStart(5, "0")}` : "-"} />
              <InfoItem label="접수시간" value={formatDateTime(selectedVisit?.visitDate)} />
              <InfoItem label="진료상태" value={selectedVisit ? STATUS_LABELS[selectedVisit.status] : "-"} />
              <InfoItem label="업로드 이미지" value={images.length > 0 ? `${images.length}장` : "-"} />
              <InfoItem label="분석 결과" value={analysis ? `${analysis.top5.length}개 후보` : "-"} />
            </div>
          </Card>
        </section>

      </div>
    </>
  );
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-16 shrink-0 text-right text-[11px] text-gray-200">{label}</span>
      <span className="min-h-[24px] flex-1 rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-gray-100">
        {value}
      </span>
    </div>
  );
}

function PrescriptionSummary({ prescription }: { prescription: import("../api/prescription").PrescriptionResponse }) {
  return (
    <div className="flex flex-col gap-2">
      {/* 상병코드 + 약품 */}
      <div className="rounded border border-gray-600 bg-side-bg p-3 flex flex-col gap-1.5">
        <div className="flex gap-2 text-xs">
          <span className="text-gray-400 w-16 shrink-0">상병코드</span>
          <div className="flex flex-col gap-0.5">
            {prescription.diseases.map(d => (
              <span key={d.kcdDiseaseId} className="text-white">
                {d.kcdCode} {d.kcdNameKr}
                {d.isPrimary && <span className="ml-1 text-[10px] text-yellow-400">주상병</span>}
              </span>
            ))}
          </div>
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

      {/* 의사 소견 */}
      {prescription.doctorNotes && (
        <div className="rounded border border-gray-600 bg-side-bg px-3 py-2 flex flex-col gap-0.5">
          <span className="text-[10px] text-gray-400 font-medium">의사 소견</span>
          <p className="text-xs text-white leading-relaxed">{prescription.doctorNotes}</p>
        </div>
      )}

      {/* AI 처방 코멘트 */}
      {prescription.aiComment && (
        <div className="rounded border border-indigo-500/30 bg-indigo-500/10 px-3 py-2 flex flex-col gap-1">
          <div className="flex items-center justify-between gap-2 flex-wrap">
            <span className="text-[10px] text-indigo-400 font-medium">
              AI 처방 코멘트{prescription.aiCommentEdited && <span className="ml-1 text-yellow-400">(수정됨)</span>}
            </span>
            {prescription.aiCommentGeneratedAt && (
              <span className="text-[10px] text-gray-500">
                생성 {formatDateTime(prescription.aiCommentGeneratedAt)}
              </span>
            )}
          </div>
          <p className="text-[11px] text-indigo-100 leading-relaxed whitespace-pre-line">
            {prescription.aiComment}
          </p>
          {prescription.aiCommentModel && (
            <p className="text-[10px] text-gray-500">{prescription.aiCommentModel}</p>
          )}
        </div>
      )}
    </div>
  );
}
