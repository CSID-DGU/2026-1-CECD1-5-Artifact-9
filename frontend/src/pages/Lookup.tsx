import { useState } from "react";

import { getPatient, searchPatients, type Patient } from "../api/patients";
import { listVisitsByPatient, type Visit, type VisitStatus } from "../api/visits";
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

  function parseChartNo(value: string) {
    const normalized = value.trim().toUpperCase().replace(/^P/, "");
    if (!normalized) return null;
    if (!/^\d+$/.test(normalized)) return Number.NaN;
    return Number(normalized);
  }

  function dedupePatients(items: Patient[]) {
    return [...new Map(items.map((patient) => [patient.id, patient])).values()];
  }

  async function handleSearch() {
    const chartNo = chartNoQuery.trim();
    const name = nameQuery.trim();
    if (!chartNo && !name) return;

    const parsedPatientId = chartNo ? parseChartNo(chartNo) : null;
    const hasInvalidChartNo = Number.isNaN(parsedPatientId);
    if (hasInvalidChartNo && !name) {
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
    try {

      const results: Patient[] = [];

      if (parsedPatientId !== null && !hasInvalidChartNo) {
        const patient = await getPatient(parsedPatientId).catch(() => null);
        if (patient) {
          results.push(patient);
        }
      }

      if (name) {
        const nameResults = await searchPatients(name);
        results.push(...nameResults);
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
    setPatients([]);
    setHasSearched(false);
    setSearchError(null);
    setSelectedPatient(null);
    setPatientDetailError(null);
    setVisits([]);
    setSelectedVisit(null);
    setVisitImages({});
  }

  async function handleSelectPatient(patient: Patient) {
    setSelectedPatient(patient);
    setPatientDetailError(null);
    setSelectedVisit(null);
    setIsLoadingPatientDetail(true);
    setIsLoadingVisits(true);
    try {
      const [patientDetail, visitList] = await Promise.all([
        getPatient(patient.id),
        listVisitsByPatient(patient.id),
      ]);
      const withRx: VisitWithPrescription[] = await Promise.all(
        visitList.map(async (v) => {
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

      const imageMap: Record<number, VisitImage[]> = {};
      await Promise.all(
        visitList.map(async (v) => {
          try { imageMap[v.id] = await listVisitImages(v.id); }
          catch { imageMap[v.id] = []; }
        })
      );
      setVisitImages(imageMap);
    } catch {
      setPatientDetailError("환자 상세 정보를 불러오지 못했습니다.");
      setVisits([]);
    } finally {
      setIsLoadingPatientDetail(false);
      setIsLoadingVisits(false);
    }
  }

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

            <div className="flex gap-2">
              <button
                onClick={handleSearch}
                disabled={isSearching || (!chartNoQuery.trim() && !nameQuery.trim())}
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
            <p className="text-xs text-gray-400 text-center py-6">내원 이력이 없습니다</p>
          ) : (
            <div className="max-h-[300px] overflow-y-auto pr-1">
              <div className="relative flex flex-col gap-2 before:absolute before:left-[7px] before:top-2 before:bottom-2 before:w-px before:bg-gray-700">
                {visits.map((visit) => {
                  const isActive = selectedVisit?.id === visit.id;
                  const imageCount = visitImages[visit.id]?.length ?? 0;

                  return (
                    <button
                      key={visit.id}
                      type="button"
                      onClick={() => setSelectedVisit(visit)}
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

      {/* Center: Visit image timeline */}
      <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
        <Card title="내원 기록" className="flex-1">
          {!selectedPatient ? (
            <p className="text-xs text-gray-400 text-center py-10">왼쪽에서 환자를 선택하세요</p>
          ) : isLoadingVisits ? (
            <p className="text-xs text-gray-400 py-4">내원 기록을 불러오는 중...</p>
          ) : visits.length === 0 ? (
            <p className="text-xs text-gray-400 text-center py-10">내원 기록이 없습니다</p>
          ) : (
            <div className="flex flex-col gap-3">
              {visits.map((v) => {
                const images = visitImages[v.id] ?? [];
                return (
                  <div key={v.id} className="border border-gray-700 rounded p-3">
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-[10px] text-blue-300">V{String(v.id).padStart(5, "0")}</span>
                        <span className="text-xs text-white font-semibold">{formatDate(v.visitDate)}</span>
                      </div>
                      <div className="flex items-center gap-2">
                        <VisitStatusBadge status={v.status} />
                        {v.prescription && (
                          <span className="text-green-400 text-[10px]">처방있음</span>
                        )}
                        <button
                          onClick={() => setSelectedVisit(v)}
                          className="px-2 py-0.5 bg-blue-600 hover:bg-blue-500 text-white text-[10px] rounded transition-colors"
                        >
                          상세
                        </button>
                      </div>
                    </div>
                    {images.length === 0 ? (
                      <p className="text-[10px] text-gray-500">등록된 이미지 없음</p>
                    ) : (
                      <div className="flex gap-2 flex-wrap">
                        {images.map((img) => (
                          <img
                            key={img.imageId}
                            src={img.imageUrl}
                            alt="내원 이미지"
                            className="w-20 h-20 object-cover rounded border border-gray-600"
                          />
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
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
              </div>
            </Card>

            {selectedVisit.prescription ? (
              <Card title="처방 정보">
                <div className="flex flex-col gap-1.5 mb-3">
                  <InfoRow label="상병코드" value={
                    <span className="font-mono text-blue-300">{selectedVisit.prescription.kcdCode}</span>
                  } />
                  <InfoRow label="상병명" value={selectedVisit.prescription.kcdNameKr} />
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
