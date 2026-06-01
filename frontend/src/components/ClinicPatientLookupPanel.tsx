import { useRef, useState } from "react";

import { listVisitImages } from "../api/images";
import { getPatient, searchPatientsByConditions, type Patient } from "../api/patients";
import { listVisitsByDate, listVisitsByPatient, type Visit, type VisitStatus } from "../api/visits";
import { Card } from "./Card";

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

type Props = {
  selectedPatientId?: number;
  selectedVisitId?: number;
  onSelectVisit: (visitId: number) => void;
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

function formatGender(gender?: string | null) {
  const map: Record<string, string> = { M: "남", F: "여", MALE: "남", FEMALE: "여", OTHER: "기타" };
  return gender ? (map[gender] ?? gender) : "-";
}

function VisitStatusBadge({ status }: { status: VisitStatus }) {
  return (
    <span className={`rounded px-2 py-0.5 text-[10px] ${STATUS_COLORS[status]}`}>
      {STATUS_LABELS[status]}
    </span>
  );
}

export function ClinicPatientLookupPanel({ selectedPatientId, selectedVisitId, onSelectVisit }: Props) {
  const [chartNoQuery, setChartNoQuery] = useState("");
  const [nameQuery, setNameQuery] = useState("");
  const [visitYearQuery, setVisitYearQuery] = useState("");
  const [visitMonthQuery, setVisitMonthQuery] = useState("");
  const [visitDayQuery, setVisitDayQuery] = useState("");
  const [patients, setPatients] = useState<Patient[]>([]);
  const [selectedLookupPatient, setSelectedLookupPatient] = useState<Patient | null>(null);
  const [visits, setVisits] = useState<Visit[]>([]);
  const [visitImageCounts, setVisitImageCounts] = useState<Record<number, number>>({});
  const [dateVisitIdsByPatient, setDateVisitIdsByPatient] = useState<Record<number, number[]>>({});
  const [activeVisitDateFilter, setActiveVisitDateFilter] = useState<string | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [isLoadingVisits, setIsLoadingVisits] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

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

  function handleDatePartChange(
    value: string,
    maxLength: number,
    onChange: (next: string) => void,
    nextRef?: React.RefObject<HTMLInputElement | null>
  ) {
    const nextValue = value.replace(/\D/g, "").slice(0, maxLength);
    onChange(nextValue);
    if (nextValue.length === maxLength) nextRef?.current?.focus();
  }

  function dedupePatients(items: Patient[]) {
    return [...new Map(items.map((patient) => [patient.id, patient])).values()];
  }

  async function handleSearch() {
    const chartNo = chartNoQuery.trim();
    const name = nameQuery.trim();
    const visitDate = visitDateQuery.trim();

    if (hasPartialVisitDate) {
      setPatients([]);
      setHasSearched(true);
      setSearchError("내원일은 연도 4자리, 월 2자리, 일 2자리를 모두 입력해 주세요.");
      return;
    }
    if (!chartNo && !name && !visitDate) return;

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
    setSelectedLookupPatient(null);
    setVisits([]);
    setVisitImageCounts({});
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
      if (hasInvalidChartNo) setSearchError("차트번호 형식이 올바르지 않아 이름으로만 검색했습니다.");
    } catch {
      setPatients([]);
      setHasSearched(true);
      setSearchError("환자 조회 중 오류가 발생했습니다.");
    } finally {
      setIsSearching(false);
    }
  }

  function handleReset() {
    setChartNoQuery("");
    setNameQuery("");
    setVisitYearQuery("");
    setVisitMonthQuery("");
    setVisitDayQuery("");
    setPatients([]);
    setSelectedLookupPatient(null);
    setVisits([]);
    setVisitImageCounts({});
    setDateVisitIdsByPatient({});
    setActiveVisitDateFilter(null);
    setHasSearched(false);
    setSearchError(null);
  }

  async function handleSelectPatient(patient: Patient) {
    setSelectedLookupPatient(patient);
    setIsLoadingVisits(true);
    setSearchError(null);

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

      setSelectedLookupPatient(patientDetail);
      setVisits(displayVisits);

      const imageCounts: Record<number, number> = {};
      await Promise.all(displayVisits.map(async (visit) => {
        try {
          imageCounts[visit.id] = (await listVisitImages(visit.id)).length;
        } catch {
          imageCounts[visit.id] = 0;
        }
      }));
      setVisitImageCounts(imageCounts);
    } catch {
      setVisits([]);
      setSearchError("환자 내원 이력을 불러오지 못했습니다.");
    } finally {
      setIsLoadingVisits(false);
    }
  }

  return (
    <div className="flex flex-col gap-[8px]">
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
              className="w-full rounded border border-gray-600 bg-side-bg px-3 py-1.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none"
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
              className="w-full rounded border border-gray-600 bg-side-bg px-3 py-1.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none"
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
                className="w-full rounded border border-gray-600 bg-side-bg px-3 py-1.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none"
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
                className="w-full rounded border border-gray-600 bg-side-bg px-3 py-1.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none"
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
                className="w-full rounded border border-gray-600 bg-side-bg px-3 py-1.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none"
              />
            </div>
          </label>

          <div className="flex gap-2">
            <button
              onClick={handleSearch}
              disabled={isSearching || (!chartNoQuery.trim() && !nameQuery.trim() && !visitDateQuery && !hasPartialVisitDate)}
              className="flex-1 rounded bg-blue-600 px-3 py-1.5 text-xs text-white transition-colors hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {isSearching ? "검색 중..." : "검색"}
            </button>
            <button
              onClick={handleReset}
              disabled={isSearching}
              className="rounded border border-gray-600 px-3 py-1.5 text-xs text-gray-200 transition-colors hover:bg-gray-700 disabled:cursor-not-allowed disabled:opacity-50"
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
            {patients.length === 0 ? (
              <p className="py-4 text-center text-xs text-gray-400">검색 결과가 없습니다</p>
            ) : (
              <div className="overflow-hidden rounded border border-gray-700">
                <div className="grid grid-cols-[80px_1fr_80px] bg-gray-950 px-2 py-2 text-[10px] font-semibold text-gray-400">
                  <span>차트번호</span>
                  <span>이름</span>
                  <span>생년월일</span>
                </div>
                {patients.map((patient) => (
                  <button
                    key={patient.id}
                    type="button"
                    onClick={() => void handleSelectPatient(patient)}
                    className={`grid w-full grid-cols-[80px_1fr_80px] items-center px-2 py-2 text-left transition-colors hover:bg-gray-700/60 ${
                      selectedLookupPatient?.id === patient.id || selectedPatientId === patient.id
                        ? "bg-blue-600/20 ring-1 ring-inset ring-blue-500/60"
                        : ""
                    }`}
                  >
                    <span className="font-mono text-[10px] text-blue-300">P{String(patient.id).padStart(5, "0")}</span>
                    <span className="min-w-0 truncate text-xs font-medium text-white">{patient.name}</span>
                    <span className="text-[10px] text-gray-400">{formatDateOnly(patient.birthDate)}</span>
                    <span className="col-span-3 mt-1 text-[10px] text-gray-400">
                      {formatGender(patient.gender)}
                      {patient.phone ? ` · ${patient.phone}` : ""}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </Card>

      <Card title="내원 타임라인">
        {!selectedLookupPatient ? (
          <p className="py-6 text-center text-xs text-gray-400">환자를 선택하면 내원 이력이 표시됩니다</p>
        ) : isLoadingVisits ? (
          <p className="py-6 text-center text-xs text-gray-400">내원 이력을 불러오는 중...</p>
        ) : visits.length === 0 ? (
          <p className="py-6 text-center text-xs text-gray-400">
            {activeVisitDateFilter ? "선택한 날짜의 내원 이력이 없습니다" : "내원 이력이 없습니다"}
          </p>
        ) : (
          <div className="max-h-[260px] overflow-y-auto pr-1">
            {activeVisitDateFilter && (
              <p className="mb-2 rounded bg-blue-500/10 px-3 py-2 text-[11px] text-blue-200">
                {activeVisitDateFilter} 내원 기록만 표시 중
              </p>
            )}
            <div className="relative flex flex-col gap-2 before:absolute before:bottom-2 before:left-[7px] before:top-2 before:w-px before:bg-gray-700">
              {visits.map((visit) => {
                const isActive = selectedVisitId === visit.id;
                return (
                  <button
                    key={visit.id}
                    type="button"
                    onClick={() => onSelectVisit(visit.id)}
                    className={`relative flex gap-3 rounded px-2 py-2 text-left transition-colors ${
                      isActive ? "bg-blue-600/20 ring-1 ring-inset ring-blue-500/60" : "hover:bg-gray-700/60"
                    }`}
                  >
                    <span className={`mt-1 h-3.5 w-3.5 shrink-0 rounded-full border-2 ${
                      isActive ? "border-blue-300 bg-blue-500" : "border-gray-500 bg-gray-800"
                    }`} />
                    <span className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="flex items-center justify-between gap-2">
                        <span className="font-mono text-[10px] text-blue-300">V{String(visit.id).padStart(5, "0")}</span>
                        <VisitStatusBadge status={visit.status} />
                      </span>
                      <span className="text-xs font-semibold text-white">{formatDate(visit.visitDate)}</span>
                      <span className="text-[10px] text-gray-400">이미지 {visitImageCounts[visit.id] ?? 0}장</span>
                    </span>
                  </button>
                );
              })}
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}
