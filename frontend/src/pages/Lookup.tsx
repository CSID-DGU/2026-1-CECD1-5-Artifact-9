import { useRef, useState } from "react";

import { getPatient, searchPatientsByConditions, type Patient } from "../api/patients";
import { listVisitsByDate, listVisitsByPatient, type Visit, type VisitStatus } from "../api/visits";
import { getPrescription, type PrescriptionResponse } from "../api/prescription";
import { listVisitImages, type VisitImage } from "../api/images";
import { Card } from "../components/Card";

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
  RECEIVED:    "bg-orange-500/20 text-orange-300",
  IN_PROGRESS: "bg-blue-500/20 text-blue-300",
  ANALYZING:   "bg-yellow-500/20 text-yellow-300",
  ANALYZED:    "bg-purple-500/20 text-purple-300",
  DIAGNOSED:   "bg-indigo-500/20 text-indigo-300",
  PRESCRIBED:  "bg-green-500/20 text-green-300",
  COMPLETED:   "bg-gray-500/20 text-gray-400",
  CANCELLED:   "bg-red-500/20 text-red-300",
};

function formatDate(value?: string | null) {
  if (!value) return "-";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function formatDateOnly(value?: string | null) {
  if (!value) return "-";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" });
}

function GenderLabel({ gender }: { gender?: string | null }) {
  if (!gender) return <>-</>;
  const map: Record<string, string> = { M: "남", F: "여", MALE: "남", FEMALE: "여", OTHER: "기타" };
  return <>{map[gender] ?? gender}</>;
}

function InfoRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex gap-2">
      <span className="w-20 text-[10px] text-gray-400 shrink-0 text-right">{label}</span>
      <span className="text-xs text-white">{value}</span>
    </div>
  );
}

function VisitStatusBadge({ status }: { status: VisitStatus }) {
  return (
    <span className={`px-2 py-0.5 rounded text-[10px] ${STATUS_COLORS[status]}`}>
      {STATUS_LABELS[status]}
    </span>
  );
}

type VisitWithPrescription = Visit & { prescription?: PrescriptionResponse | null };

export default function Lookup() {
  const [chartNoQuery, setChartNoQuery] = useState("");
  const [nameQuery, setNameQuery] = useState("");
  const [visitYearQuery, setVisitYearQuery] = useState("");
  const [visitMonthQuery, setVisitMonthQuery] = useState("");
  const [visitDayQuery, setVisitDayQuery] = useState("");
  const [isSearching, setIsSearching] = useState(false);
  const [patients, setPatients] = useState<Patient[]>([]);
  const [hasSearched, setHasSearched] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [isLoadingPatientDetail, setIsLoadingPatientDetail] = useState(false);
  const [patientDetailError, setPatientDetailError] = useState<string | null>(null);
  const [visits, setVisits] = useState<VisitWithPrescription[]>([]);
  const [isLoadingVisits, setIsLoadingVisits] = useState(false);
  const [selectedVisit, setSelectedVisit] = useState<VisitWithPrescription | null>(null);
  const [visitImages, setVisitImages] = useState<Record<number, VisitImage[]>>({});
  const [selectedImageIds, setSelectedImageIds] = useState<number[]>([]);
  const [activeVisitDateFilter, setActiveVisitDateFilter] = useState<string | null>(null);
  const [dateVisitIdsByPatient, setDateVisitIdsByPatient] = useState<Record<number, number[]>>({});
  const monthInputRef = useRef<HTMLInputElement>(null);
  const dayInputRef = useRef<HTMLInputElement>(null);

  const visitDateQuery =
    visitYearQuery.length === 4 && visitMonthQuery.length === 2 && visitDayQuery.length === 2
      ? `${visitYearQuery}-${visitMonthQuery}-${visitDayQuery}`
      : "";
  const hasPartialVisitDate = Boolean(visitYearQuery || visitMonthQuery || visitDayQuery) && !visitDateQuery;

  function parseChartNo(value: string) {
    const normalized = value.trim().toUpperCase().replace(/^P/, "");
    if (!normalized) return null;
    if (!/^\d+$/.test(normalized)) return Number.NaN;
    return Number(normalized);
  }

  function dedupePatients(items: Patient[]) {
    return [...new Map(items.map((patient) => [patient.id, patient])).values()];
  }

  function describeSearchConditions() {
    const conditions = [];
    if (chartNoQuery.trim()) conditions.push(`차트번호 ${chartNoQuery.trim()}`);
    if (nameQuery.trim()) conditions.push(`이름 ${nameQuery.trim()}`);
    if (visitDateQuery.trim()) conditions.push(`내원일 ${visitDateQuery.trim()}`);
    return conditions;
  }

  function handleDatePartChange(value: string, maxLength: number, onChange: (next: string) => void, nextRef?: React.RefObject<HTMLInputElement | null>) {
    const nextValue = value.replace(/\D/g, "").slice(0, maxLength);
    onChange(nextValue);

    if (nextValue.length === maxLength) {
      nextRef?.current?.focus();
    }
  }

  function handleSelectVisit(visit: VisitWithPrescription) {
    setSelectedVisit(visit);
    setSelectedImageIds([]);
  }

  function toggleSelectedImage(imageId: number) {
    setSelectedImageIds((current) =>
      current.includes(imageId)
        ? current.filter((id) => id !== imageId)
        : [...current, imageId]
    );
  }

  async function handleSearch() {
    const chartNo = chartNoQuery.trim();
    const name = nameQuery.trim();
    const visitDate = visitDateQuery.trim();
    if (!chartNo && !name && !visitDate) return;
    if (hasPartialVisitDate) {
      setPatients([]);
      setHasSearched(true);
      setSearchError("내원일은 연도 4자리, 월 2자리, 일 2자리를 모두 입력해 주세요.");
      return;
    }

    const parsedPatientId = chartNo ? parseChartNo(chartNo) : null;
    const hasInvalidChartNo = Number.isNaN(parsedPatientId);
    if (hasInvalidChartNo && !name && !visitDate) {
      setPatients([]);
      setHasSearched(true);
      setSearchError("차트번호는 P00001 또는 숫자 형식으로 입력해 주세요.");
      return;
    }

    setIsSearching(true);
    setSearchError(null);
    setSelectedPatient(null);
    setPatientDetailError(null);
    setVisits([]);
    setSelectedVisit(null);
    setVisitImages({});
    setSelectedImageIds([]);
    setActiveVisitDateFilter(visitDate || null);
    setDateVisitIdsByPatient({});
    try {

      const results = await searchPatientsByConditions({
        patientId: parsedPatientId !== null && !hasInvalidChartNo ? parsedPatientId : null,
        name,
        visitDate,
      });

      if (visitDate) {
        const visitResults = await listVisitsByDate(visitDate);
        const visitIdsByPatient = visitResults.reduce<Record<number, number[]>>((acc, visit) => {
          acc[visit.patientId] = [...(acc[visit.patientId] ?? []), visit.id];
          return acc;
        }, {});
        setDateVisitIdsByPatient(visitIdsByPatient);
      }

      setPatients(dedupePatients(results));

      setHasSearched(true);
      if (hasInvalidChartNo) {
        setSearchError("차트번호 형식이 올바르지 않아 이름으로만 검색했습니다.");
      }
    } catch {
      setPatients([]);
      setHasSearched(true);
      setSearchError("환자 조회 중 오류가 발생했습니다.");
    } finally {
      setIsSearching(false);
    }
  }

  function handleResetSearch() {
    setChartNoQuery("");
    setNameQuery("");
    setVisitYearQuery("");
    setVisitMonthQuery("");
    setVisitDayQuery("");
    setPatients([]);
    setHasSearched(false);
    setSearchError(null);
    setSelectedPatient(null);
    setPatientDetailError(null);
    setVisits([]);
    setSelectedVisit(null);
    setVisitImages({});
    setSelectedImageIds([]);
    setActiveVisitDateFilter(null);
    setDateVisitIdsByPatient({});
  }

  async function handleSelectPatient(patient: Patient) {
    setSelectedPatient(patient);
    setPatientDetailError(null);
    setSelectedVisit(null);
    setSelectedImageIds([]);
    setIsLoadingPatientDetail(true);
    setIsLoadingVisits(true);
    try {
      const [patientDetail, visitList] = await Promise.all([
        getPatient(patient.id),
        listVisitsByPatient(patient.id),
      ]);
      const dateMatchedVisitIds = activeVisitDateFilter
        ? new Set(dateVisitIdsByPatient[patient.id] ?? [])
        : null;
      const displayVisits = dateMatchedVisitIds && dateMatchedVisitIds.size > 0
        ? visitList.filter((visit) => dateMatchedVisitIds.has(visit.id))
        : visitList;
      const withRx: VisitWithPrescription[] = await Promise.all(
        displayVisits.map(async (v) => {
          if (v.status === "PRESCRIBED" || v.status === "COMPLETED") {
            try {
              const rx = await getPrescription(v.id);
              return { ...v, prescription: rx };
            } catch {
              return { ...v, prescription: null };
            }
          }
          return { ...v, prescription: null };
        })
      );
      setSelectedPatient(patientDetail);
      setVisits(withRx);
      setSelectedVisit(withRx[0] ?? null);

      const imageMap: Record<number, VisitImage[]> = {};
      await Promise.all(
        displayVisits.map(async (v) => {
          try { imageMap[v.id] = await listVisitImages(v.id); }
          catch { imageMap[v.id] = []; }
        })
      );
      setVisitImages(imageMap);
    } catch {
      setPatientDetailError("환자 상세 정보를 불러오지 못했습니다.");
      setVisits([]);
      setSelectedVisit(null);
      setSelectedImageIds([]);
    } finally {
      setIsLoadingPatientDetail(false);
      setIsLoadingVisits(false);
    }
  }

  const selectedVisitImages = selectedVisit ? (visitImages[selectedVisit.id] ?? []) : [];

  return (
    <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">
      {/* Left: Search */}
      <section className="w-[360px] flex flex-col shrink-0 gap-[8px]">
        <Card title="환자 조회">
          <div className="flex flex-col gap-2">
            <label className="flex flex-col gap-1">
              <span className="text-[10px] text-gray-400">차트번호</span>
              <input
                type="text"
                value={chartNoQuery}
                onChange={(e) => setChartNoQuery(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                placeholder="P00001 또는 숫자"
                className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors placeholder-gray-500"
              />
            </label>

            <label className="flex flex-col gap-1">
              <span className="text-[10px] text-gray-400">이름</span>
              <input
                type="text"
                value={nameQuery}
                onChange={(e) => setNameQuery(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                placeholder="환자 이름"
                className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors placeholder-gray-500"
              />
            </label>

            <label className="flex flex-col gap-1">
              <span className="text-[10px] text-gray-400">내원일</span>
              <div className="grid grid-cols-[1fr_58px_58px] items-center gap-1.5">
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={4}
                  value={visitYearQuery}
                  onChange={(e) => handleDatePartChange(e.target.value, 4, setVisitYearQuery, monthInputRef)}
                  onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                  placeholder="YYYY"
                  aria-label="내원일 연도"
                  className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors placeholder-gray-500"
                />
                <input
                  ref={monthInputRef}
                  type="text"
                  inputMode="numeric"
                  maxLength={2}
                  value={visitMonthQuery}
                  onChange={(e) => handleDatePartChange(e.target.value, 2, setVisitMonthQuery, dayInputRef)}
                  onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                  placeholder="MM"
                  aria-label="내원일 월"
                  className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors placeholder-gray-500"
                />
                <input
                  ref={dayInputRef}
                  type="text"
                  inputMode="numeric"
                  maxLength={2}
                  value={visitDayQuery}
                  onChange={(e) => handleDatePartChange(e.target.value, 2, setVisitDayQuery)}
                  onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                  placeholder="DD"
                  aria-label="내원일 일"
                  className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors placeholder-gray-500"
                />
              </div>
            </label>

            <div className="flex gap-2">
              <button
                onClick={handleSearch}
                disabled={isSearching || (!chartNoQuery.trim() && !nameQuery.trim() && !visitDateQuery.trim() && !hasPartialVisitDate)}
                className="flex-1 px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-xs text-white rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {isSearching ? "검색 중..." : "검색"}
              </button>
              <button
                onClick={handleResetSearch}
                disabled={isSearching}
                className="px-3 py-1.5 border border-gray-600 hover:bg-gray-700 text-xs text-gray-200 rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                초기화
              </button>
            </div>
          </div>

          {searchError && (
            <p className="mt-3 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-[11px] text-red-200">
              {searchError}
            </p>
          )}

          {hasSearched && (
            <div className="mt-3">
              {describeSearchConditions().length > 0 && (
                <p className="mb-2 rounded bg-gray-900/70 px-3 py-2 text-[11px] text-gray-300">
                  {describeSearchConditions().join(" · ")} 조건으로 검색했습니다.
                </p>
              )}
              {patients.length === 0 ? (
                <p className="text-xs text-gray-400 text-center py-4">검색 결과가 없습니다</p>
              ) : (
                <div className="overflow-x-auto rounded border border-gray-700">
                  <div className="grid grid-cols-[86px_1fr_82px] bg-gray-950 px-2 py-2 text-[10px] font-semibold text-gray-400">
                    <span>차트번호</span>
                    <span>이름</span>
                    <span>생년월일</span>
                  </div>
                  {patients.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => handleSelectPatient(p)}
                      className={`grid w-full grid-cols-[86px_1fr_82px] items-center gap-0 px-2 py-2 text-left hover:bg-gray-700/60 transition-colors ${
                        selectedPatient?.id === p.id ? "bg-blue-600/20 ring-1 ring-inset ring-blue-500/60" : ""
                      }`}
                    >
                      <span className="font-mono text-[10px] text-blue-300">P{String(p.id).padStart(5, "0")}</span>
                      <span className="min-w-0 truncate text-xs font-medium text-white">{p.name}</span>
                      <span className="text-[10px] text-gray-400">{formatDateOnly(p.birthDate)}</span>
                      <span className="col-span-3 mt-1 text-[10px] text-gray-400">
                        <GenderLabel gender={p.gender} />
                        {p.phone ? ` · ${p.phone}` : ""}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </Card>

        <Card title="내원 타임라인">
          {!selectedPatient ? (
            <p className="text-xs text-gray-400 text-center py-6">환자를 선택하면 내원 이력이 표시됩니다</p>
          ) : isLoadingVisits ? (
            <p className="text-xs text-gray-400 text-center py-6">내원 이력을 불러오는 중...</p>
          ) : visits.length === 0 ? (
            <p className="text-xs text-gray-400 text-center py-6">
              {activeVisitDateFilter ? "선택한 날짜의 내원 이력이 없습니다" : "내원 이력이 없습니다"}
            </p>
          ) : (
            <div className="max-h-[300px] overflow-y-auto pr-1">
              {activeVisitDateFilter && (
                <p className="mb-2 rounded bg-blue-500/10 px-3 py-2 text-[11px] text-blue-200">
                  {activeVisitDateFilter} 내원 기록만 표시 중
                </p>
              )}
              <div className="relative flex flex-col gap-2 before:absolute before:left-[7px] before:top-2 before:bottom-2 before:w-px before:bg-gray-700">
                {visits.map((visit) => {
                  const isActive = selectedVisit?.id === visit.id;
                  const imageCount = visitImages[visit.id]?.length ?? 0;

                  return (
                    <button
                      key={visit.id}
                      type="button"
                      onClick={() => handleSelectVisit(visit)}
                      className={`relative flex gap-3 rounded px-2 py-2 text-left transition-colors ${
                        isActive
                          ? "bg-blue-600/20 ring-1 ring-inset ring-blue-500/60"
                          : "hover:bg-gray-700/60"
                      }`}
                    >
                      <span className={`mt-1 h-3.5 w-3.5 shrink-0 rounded-full border-2 ${
                        isActive ? "border-blue-300 bg-blue-500" : "border-gray-500 bg-gray-800"
                      }`} />
                      <span className="flex min-w-0 flex-1 flex-col gap-1">
                        <span className="flex items-center justify-between gap-2">
                          <span className="font-mono text-[10px] text-blue-300">
                            V{String(visit.id).padStart(5, "0")}
                          </span>
                          <VisitStatusBadge status={visit.status} />
                        </span>
                        <span className="text-xs font-semibold text-white">
                          {formatDate(visit.visitDate)}
                        </span>
                        <span className="text-[10px] text-gray-400">
                          이미지 {imageCount}장
                          {visit.prescription ? " · 처방있음" : ""}
                        </span>
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </Card>

        <Card title="환자 정보">
          {!selectedPatient ? (
            <p className="text-xs text-gray-400 text-center py-6">조회 결과에서 환자를 선택하세요</p>
          ) : (
            <div className="flex flex-col gap-2">
              {isLoadingPatientDetail && (
                <p className="rounded bg-blue-500/10 px-3 py-2 text-[11px] text-blue-200">
                  환자 상세 정보를 불러오는 중...
                </p>
              )}
              {patientDetailError && (
                <p className="rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-[11px] text-red-200">
                  {patientDetailError}
                </p>
              )}
              <div className="flex flex-col gap-1.5">
                <InfoRow label="환자번호" value={<span className="font-mono text-blue-300">P{String(selectedPatient.id).padStart(5, "0")}</span>} />
                <InfoRow label="성명" value={selectedPatient.name} />
                <InfoRow label="성별" value={<GenderLabel gender={selectedPatient.gender} />} />
                <InfoRow label="생년월일" value={formatDateOnly(selectedPatient.birthDate)} />
                <InfoRow label="연락처" value={selectedPatient.phone ?? "-"} />
                <InfoRow label="등록일시" value={formatDate(selectedPatient.createdAt)} />
                {selectedPatient.memo && <InfoRow label="메모" value={selectedPatient.memo} />}
              </div>
            </div>
          )}
        </Card>
      </section>

      {/* Center: Selected visit images */}
      <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
        <Card title="선택 내원 이미지" className="flex-1">
          {!selectedPatient ? (
            <p className="text-xs text-gray-400 text-center py-10">왼쪽에서 환자를 선택하세요</p>
          ) : isLoadingVisits ? (
            <p className="text-xs text-gray-400 py-4">내원 이미지를 불러오는 중...</p>
          ) : visits.length === 0 ? (
            <p className="text-xs text-gray-400 text-center py-10">내원 기록이 없습니다</p>
          ) : !selectedVisit ? (
            <p className="text-xs text-gray-400 text-center py-10">내원 타임라인에서 내원을 선택하세요</p>
          ) : (
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between rounded border border-gray-700 bg-gray-900/40 px-3 py-2">
                <div className="flex flex-col gap-1">
                  <span className="font-mono text-[10px] text-blue-300">
                    V{String(selectedVisit.id).padStart(5, "0")}
                  </span>
                  <span className="text-xs font-semibold text-white">
                    {formatDate(selectedVisit.visitDate)}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <VisitStatusBadge status={selectedVisit.status} />
                  <span className="text-[10px] text-gray-400">
                    이미지 {selectedVisitImages.length}장
                  </span>
                  {selectedImageIds.length > 0 && (
                    <span className="rounded bg-blue-500/20 px-2 py-0.5 text-[10px] text-blue-200">
                      선택 {selectedImageIds.length}장
                    </span>
                  )}
                </div>
              </div>

              {selectedVisitImages.length === 0 ? (
                <p className="text-xs text-gray-400 text-center py-10">선택한 내원에 등록된 이미지가 없습니다</p>
              ) : (
                <div className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-3">
                  {selectedVisitImages.map((image) => {
                    const isSelected = selectedImageIds.includes(image.imageId);

                    return (
                      <button
                        key={image.imageId}
                        type="button"
                        onClick={() => toggleSelectedImage(image.imageId)}
                        className={`overflow-hidden rounded border bg-gray-900 text-left transition-colors ${
                          isSelected
                            ? "border-blue-400 ring-2 ring-blue-500/50"
                            : "border-gray-700 hover:border-blue-500/60"
                        }`}
                      >
                        <img
                          src={image.imageUrl}
                          alt={`내원 이미지 ${image.imageId}`}
                          className="h-32 w-full object-cover"
                        />
                        <div className="flex flex-col gap-1 px-2 py-2">
                          <span className="font-mono text-[10px] text-blue-300">
                            #{image.imageId}
                          </span>
                          <span className="text-[10px] text-gray-400">
                            {formatDate(image.uploadedAt)}
                          </span>
                        </div>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </Card>
      </section>

      {/* Right: Visit detail */}
      <section className="w-[320px] flex flex-col shrink-0 gap-[8px] overflow-y-auto">
        {selectedVisit ? (
          <>
            <Card title="진료 상세">
              <div className="flex flex-col gap-1.5">
                <InfoRow label="접수번호" value={<span className="font-mono text-blue-300">V{String(selectedVisit.id).padStart(5, "0")}</span>} />
                <InfoRow label="내원일시" value={formatDate(selectedVisit.visitDate)} />
                <InfoRow label="상태" value={
                  <VisitStatusBadge status={selectedVisit.status} />
                } />
                <InfoRow label="이미지" value={`${selectedVisitImages.length}장`} />
              </div>
            </Card>

            {selectedVisit.prescription ? (
              <Card title="처방 정보">
                <div className="flex flex-col gap-1.5 mb-3">
                  <InfoRow label="상병" value={
                    selectedVisit.prescription.diseases.length > 0 ? (
                      <span className="flex flex-col gap-1">
                        {selectedVisit.prescription.diseases.map((disease) => (
                          <span key={`${disease.kcdDiseaseId}-${disease.kcdCode}`}>
                            <span className="font-mono text-blue-300">{disease.kcdCode}</span>
                            <span className="ml-1">{disease.kcdNameKr}</span>
                            <span className="ml-1 text-[10px] text-gray-400">
                              {disease.isPrimary ? "주상병" : "부상병"}
                            </span>
                          </span>
                        ))}
                      </span>
                    ) : "-"
                  } />
                  <InfoRow label="처방일시" value={formatDate(selectedVisit.prescription.prescribedAt)} />
                  {selectedVisit.prescription.revisitRecommendedDate && (
                    <InfoRow label="재방문권장일" value={formatDateOnly(selectedVisit.prescription.revisitRecommendedDate)} />
                  )}
                  {selectedVisit.prescription.doctorNotes && (
                    <InfoRow label="의사소견" value={selectedVisit.prescription.doctorNotes} />
                  )}
                </div>

                {selectedVisit.prescription.details.length > 0 && (
                  <>
                    <p className="text-[10px] text-gray-400 mb-1.5 font-semibold">처방 약품</p>
                    <div className="flex flex-col divide-y divide-gray-700">
                      {selectedVisit.prescription.details.map((d, i) => (
                        <div key={i} className="py-2 flex flex-col gap-0.5">
                          <span className="text-xs text-white font-medium">{d.medicineName}</span>
                          <div className="flex gap-3 text-[10px] text-gray-400">
                            {d.dosage && <span>용량: {d.dosage}</span>}
                            {d.durationDays && <span>{d.durationDays}일분</span>}
                            {d.notes && <span>{d.notes}</span>}
                          </div>
                        </div>
                      ))}
                    </div>
                  </>
                )}
              </Card>
            ) : (
              <Card title="처방 정보">
                <p className="text-xs text-gray-400 text-center py-4">처방 정보가 없습니다</p>
              </Card>
            )}
          </>
        ) : (
          <Card title="진료 상세">
            <p className="text-xs text-gray-400 text-center py-10">내원 기록에서 상세 버튼을 선택하세요</p>
          </Card>
        )}
      </section>
    </div>
  );
}
