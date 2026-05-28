import { useState } from "react";
import { searchPatients, type Patient } from "../api/patients";
import { listVisitsByPatient, type Visit, type VisitStatus } from "../api/visits";
import { getPrescription, type PrescriptionResponse } from "../api/prescription";
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

type VisitWithPrescription = Visit & { prescription?: PrescriptionResponse | null };

export default function Lookup() {
  const [query, setQuery] = useState("");
  const [isSearching, setIsSearching] = useState(false);
  const [patients, setPatients] = useState<Patient[]>([]);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);
  const [visits, setVisits] = useState<VisitWithPrescription[]>([]);
  const [isLoadingVisits, setIsLoadingVisits] = useState(false);
  const [selectedVisit, setSelectedVisit] = useState<VisitWithPrescription | null>(null);

  async function handleSearch() {
    if (!query.trim()) return;
    setIsSearching(true);
    setSelectedPatient(null);
    setVisits([]);
    setSelectedVisit(null);
    try {
      const results = await searchPatients(query.trim());
      setPatients(results);
      setHasSearched(true);
    } catch {
      setPatients([]);
      setHasSearched(true);
    } finally {
      setIsSearching(false);
    }
  }

  async function handleSelectPatient(patient: Patient) {
    setSelectedPatient(patient);
    setSelectedVisit(null);
    setIsLoadingVisits(true);
    try {
      const visitList = await listVisitsByPatient(patient.id);
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
      setVisits(withRx);
    } catch {
      setVisits([]);
    } finally {
      setIsLoadingVisits(false);
    }
  }

  const visitTableData = visits.map((v, idx) => [
    idx + 1,
    `V${String(v.id).padStart(5, "0")}`,
    formatDate(v.visitDate),
    <span key={`s-${v.id}`} className={`px-2 py-0.5 rounded text-[10px] ${STATUS_COLORS[v.status]}`}>
      {STATUS_LABELS[v.status]}
    </span>,
    v.prescription ? (
      <span key={`rx-${v.id}`} className="text-green-400 text-[10px]">처방있음</span>
    ) : (
      <span key={`rx-${v.id}`} className="text-gray-500 text-[10px]">-</span>
    ),
    <button
      key={`sel-${v.id}`}
      onClick={() => setSelectedVisit(v)}
      className="px-2 py-0.5 bg-blue-600 hover:bg-blue-500 text-white text-[10px] rounded transition-colors"
    >
      상세
    </button>,
  ]);

  return (
    <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">
      {/* Left: Search */}
      <section className="w-[300px] flex flex-col shrink-0 gap-[8px]">
        <Card title="환자 조회">
          <div className="flex gap-2">
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleSearch()}
              placeholder="환자 이름 입력 후 Enter"
              className="flex-1 min-w-0 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors placeholder-gray-500"
            />
            <button
              onClick={handleSearch}
              disabled={isSearching || !query.trim()}
              className="shrink-0 px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-xs text-white rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isSearching ? "..." : "검색"}
            </button>
          </div>

          {hasSearched && (
            <div className="mt-3">
              {patients.length === 0 ? (
                <p className="text-xs text-gray-400 text-center py-4">검색 결과가 없습니다</p>
              ) : (
                <div className="flex flex-col divide-y divide-gray-700">
                  {patients.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => handleSelectPatient(p)}
                      className={`w-full text-left px-2 py-2.5 hover:bg-gray-700/60 transition-colors rounded ${
                        selectedPatient?.id === p.id ? "bg-blue-600/20 border border-blue-600/40" : ""
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-white font-medium">{p.name}</span>
                        <span className="font-mono text-[10px] text-blue-300">P{String(p.id).padStart(5, "0")}</span>
                      </div>
                      <div className="text-[10px] text-gray-400 mt-0.5">
                        <GenderLabel gender={p.gender} />
                        {p.birthDate ? ` · ${formatDateOnly(p.birthDate)}` : ""}
                        {p.phone ? ` · ${p.phone}` : ""}
                      </div>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </Card>

        {selectedPatient && (
          <Card title="환자 정보">
            <div className="flex flex-col gap-1.5">
              <InfoRow label="환자번호" value={<span className="font-mono text-blue-300">P{String(selectedPatient.id).padStart(5, "0")}</span>} />
              <InfoRow label="성명" value={selectedPatient.name} />
              <InfoRow label="성별" value={<GenderLabel gender={selectedPatient.gender} />} />
              <InfoRow label="생년월일" value={formatDateOnly(selectedPatient.birthDate)} />
              <InfoRow label="연락처" value={selectedPatient.phone ?? "-"} />
              {selectedPatient.memo && <InfoRow label="메모" value={selectedPatient.memo} />}
            </div>
          </Card>
        )}
      </section>

      {/* Center: Visit list */}
      <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
        <Card title="내원 기록" className="flex-1">
          {!selectedPatient ? (
            <p className="text-xs text-gray-400 text-center py-10">왼쪽에서 환자를 선택하세요</p>
          ) : isLoadingVisits ? (
            <p className="text-xs text-gray-400 py-4">내원 기록을 불러오는 중...</p>
          ) : visits.length === 0 ? (
            <p className="text-xs text-gray-400 text-center py-10">내원 기록이 없습니다</p>
          ) : (
            <Table
              headers={["순번", "접수번호", "내원일시", "상태", "처방", "상세"]}
              data={visitTableData}
            />
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
                  <span className={`px-2 py-0.5 rounded text-[10px] ${STATUS_COLORS[selectedVisit.status]}`}>
                    {STATUS_LABELS[selectedVisit.status]}
                  </span>
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
