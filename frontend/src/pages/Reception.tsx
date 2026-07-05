import { useEffect, useState } from "react";
import {
  createPatient,
  getPatient,
  searchPatientsForReception,
  type PatientSearchResult,
} from "../api/patients";
import { createVisit, listVisits, type Visit, type VisitStatus } from "../api/visits";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Input } from "../components/Input";
import { Table } from "../components/Table";

// ─── 상수 ────────────────────────────────────────────────
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
  RECEIVED:   "bg-orange-500/20 text-orange-300",
  IN_PROGRESS:"bg-blue-500/20 text-blue-300",
  ANALYZING:  "bg-yellow-500/20 text-yellow-300",
  ANALYZED:   "bg-purple-500/20 text-purple-300",
  DIAGNOSED:  "bg-indigo-500/20 text-indigo-300",
  PRESCRIBED: "bg-green-500/20 text-green-300",
  COMPLETED:  "bg-gray-500/20 text-gray-400",
  CANCELLED:  "bg-red-500/20 text-red-300",
};

const GENDER_LABELS: Record<string, string> = { M: "남", F: "여", OTHER: "기타" };

// ─── 헬퍼 ────────────────────────────────────────────────
function formatDateTime(value?: string) {
  if (!value) return "-";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "요청 처리 중 오류가 발생했습니다.";
}

// ─── 하위 컴포넌트 ────────────────────────────────────────
function SelectField({
  label, value, onChange, options, disabled = false,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center gap-2">
      <label className="w-24 text-xs text-white shrink-0 text-right pr-2">{label}</label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        className="flex-1 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500 transition-colors disabled:cursor-not-allowed disabled:opacity-60"
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    </div>
  );
}

function MockField({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-2">
      <label className="w-24 text-xs text-gray-500 shrink-0 text-right pr-2">{label}</label>
      <div className="flex-1 px-3 py-1.5 rounded bg-side-bg border border-gray-700 text-sm text-gray-500 cursor-not-allowed select-none">
        {value}
      </div>
    </div>
  );
}

/** 검색 입력 전용 컴팩트 Input (라벨 없음, placeholder만) */
function SearchInput({
  placeholder, value, onChange, type = "text", onEnter,
}: {
  placeholder: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  onEnter?: () => void;
}) {
  return (
    <input
      type={type}
      placeholder={placeholder}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => e.key === "Enter" && onEnter?.()}
      className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-400 transition-colors placeholder-gray-500"
    />
  );
}

// ─── 메인 컴포넌트 ─────────────────────────────────────────
type ReceptionMode = "new" | "existing";

export default function Reception() {

  // 검색 입력
  const [searchName, setSearchName]       = useState("");
  const [searchBirthDate, setSearchBirthDate] = useState("");
  const [searchGender, setSearchGender]   = useState("");
  const [searchPhone, setSearchPhone]     = useState("");
  const [searchResults, setSearchResults] = useState<PatientSearchResult[]>([]);
  const [isSearching, setIsSearching]     = useState(false);
  const [hasSearched, setHasSearched]     = useState(false);

  // 모드 및 선택된 환자
  const [mode, setMode]                         = useState<ReceptionMode>("new");
  const [selectedPatient, setSelectedPatient]   = useState<PatientSearchResult | null>(null);

  // 환자정보 폼 필드
  const [name, setName]           = useState("");
  const [gender, setGender]       = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [phone, setPhone]         = useState("");
  const [memo, setMemo]           = useState(""); // visit.receptionMemo

  // 진료현황 목록
  const [activeTab, setActiveTab]             = useState<"대기" | "완료">("대기");
  const [receivedVisits, setReceivedVisits]   = useState<Visit[]>([]);
  const [completedVisits, setCompletedVisits] = useState<Visit[]>([]);
  const [patientNameMap, setPatientNameMap]   = useState<Map<number, string>>(new Map());
  const [isListLoading, setIsListLoading]     = useState(false);

  // 제출 상태
  const [isSubmitting, setIsSubmitting]   = useState(false);
  const [message, setMessage]             = useState<string | null>(null);
  const [errorMessage, setErrorMessage]   = useState<string | null>(null);

  // ── 진료현황 로드 ───────────────────────────────────────
  async function loadVisits() {
    setIsListLoading(true);
    try {
      const [received, completed] = await Promise.all([
        listVisits("RECEIVED"),
        listVisits("COMPLETED"),
      ]);
      setReceivedVisits(received);
      setCompletedVisits(completed);

      const allVisits = [...received, ...completed];
      const uniqueIds = [...new Set(allVisits.map((v) => v.patientId))];
      const patients  = await Promise.all(uniqueIds.map((id) => getPatient(id).catch(() => null)));
      const nameMap   = new Map<number, string>();
      uniqueIds.forEach((id, i) => { if (patients[i]) nameMap.set(id, patients[i]!.name); });
      setPatientNameMap(nameMap);
    } catch {
      // 목록 로딩 실패는 조용히 처리
    } finally {
      setIsListLoading(false);
    }
  }

  useEffect(() => { loadVisits(); }, []);

  // ── 검색 ───────────────────────────────────────────────
  async function handleSearch() {
    if (!searchName.trim()) {
      setErrorMessage("이름을 입력하세요.");
      return;
    }
    setIsSearching(true);
    setHasSearched(true);
    setErrorMessage(null);
    setSelectedPatient(null);
    setMode("new");
    try {
      const results = await searchPatientsForReception({
        name:      searchName.trim(),
        birthDate: searchBirthDate || undefined,
        gender:    searchGender    || undefined,
        phone:     searchPhone     || undefined,
      });
      setSearchResults(results);
    } catch {
      setSearchResults([]);
    } finally {
      setIsSearching(false);
    }
  }

  // ── 기존 환자 선택 ──────────────────────────────────────
  function handleSelectPatient(p: PatientSearchResult) {
    setSelectedPatient(p);
    setMode("existing");
    setName(p.name);
    setGender(p.gender ?? "");
    setBirthDate(p.birthDate ?? "");
    setPhone(p.phone ?? "");
    setMemo("");
    setSearchResults([]);
    setHasSearched(false);
    setErrorMessage(null);
    setMessage(null);
  }

  // ── 신규 환자 모드 전환 ─────────────────────────────────
  function handleNewPatient() {
    setSelectedPatient(null);
    setMode("new");
    setName(searchName); // 검색에 입력한 이름을 폼에 미리 채움
    setGender("");
    setBirthDate("");
    setPhone("");
    setMemo("");
    setSearchResults([]);
    setHasSearched(false);
    setErrorMessage(null);
  }

  // ── 전체 초기화 ─────────────────────────────────────────
  function handleReset() {
    setSelectedPatient(null);
    setMode("new");
    setName(""); setGender(""); setBirthDate(""); setPhone(""); setMemo("");
    setSearchName(""); setSearchBirthDate(""); setSearchGender(""); setSearchPhone("");
    setSearchResults([]);
    setHasSearched(false);
    setMessage(null);
    setErrorMessage(null);
  }

  // ── 접수 등록 ───────────────────────────────────────────
  async function handleSubmit() {
    setIsSubmitting(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      let patientId: number;
      let patientName: string;

      if (mode === "existing" && selectedPatient) {
        // 기존 환자: patient_id로 Visit만 생성
        patientId   = selectedPatient.patientId;
        patientName = selectedPatient.name;
      } else {
        // 신규 환자: Patient 생성 후 Visit 생성
        if (!name.trim()) {
          setErrorMessage("성명은 필수 항목입니다.");
          return;
        }
        const patient = await createPatient({
          name:      name.trim(),
          gender:    (gender as "M" | "F" | "OTHER") || null,
          birthDate: birthDate || null,
          phone:     phone.trim() || null,
          memo:      null,
        });
        patientId   = patient.id;
        patientName = patient.name;
      }

      const visit = await createVisit(patientId, memo.trim() || null);

      const patientNo = `P${String(patientId).padStart(5, "0")}`;
      const visitNo   = `V${String(visit.id).padStart(5, "0")}`;

      // 폼 초기화 (메시지는 유지)
      setSelectedPatient(null);
      setMode("new");
      setName(""); setGender(""); setBirthDate(""); setPhone(""); setMemo("");
      setSearchName(""); setSearchBirthDate(""); setSearchGender(""); setSearchPhone("");
      setSearchResults([]);
      setHasSearched(false);

      setMessage(`접수 완료 — 환자번호 ${patientNo} (${patientName}) / 접수번호 ${visitNo}`);
      setActiveTab("대기");
      await loadVisits();
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  }

  // ── 파생 값 ────────────────────────────────────────────
  const isExisting     = mode === "existing";
  const formDisabled   = isExisting || isSubmitting;
  const canSubmit      = !isSubmitting && (isExisting ? true : name.trim().length > 0);
  const activeVisits   = activeTab === "대기" ? receivedVisits : completedVisits;

  // 검색 결과 테이블 데이터
  const searchTableData = searchResults.map((p) => [
    p.name,
    p.birthDate ?? "-",
    p.gender ? (GENDER_LABELS[p.gender] ?? p.gender) : "-",
    p.maskedPhone ?? "-",
    `P${String(p.patientId).padStart(5, "0")}`,
    p.lastVisitDate ?? "없음",
    <button
      key={p.patientId}
      onClick={() => handleSelectPatient(p)}
      className="px-2 py-0.5 rounded bg-blue-600 hover:bg-blue-500 text-white text-[11px] font-medium transition-colors cursor-pointer"
    >
      선택
    </button>,
  ]);

  // 진료현황 테이블 데이터
  const visitTableData = activeVisits.map((visit, idx) => [
    idx + 1,
    `V${String(visit.id).padStart(5, "0")}`,
    `P${String(visit.patientId).padStart(5, "0")}`,
    patientNameMap.get(visit.patientId) ?? "-",
    formatDateTime(visit.visitDate),
    <span key={`s-${visit.id}`} className={`px-2 py-0.5 rounded text-[10px] ${STATUS_COLORS[visit.status]}`}>
      {STATUS_LABELS[visit.status]}
    </span>,
  ]);

  // ── 렌더 ───────────────────────────────────────────────
  return (
    <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">

      {/* ── 중앙 컬럼 (폼 영역) ── */}
      <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto min-w-0">

        {/* 전역 메시지 */}
        {message && (
          <p className="shrink-0 rounded border border-green-500/40 bg-green-500/10 px-3 py-2 text-xs text-green-100">
            {message}
          </p>
        )}
        {errorMessage && (
          <p className="shrink-0 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-xs text-red-200">
            {errorMessage}
          </p>
        )}

        {/* ── 환자 검색 박스 ── */}
        <Card title="환자 검색" className="shrink-0">
          <div className="grid grid-cols-4 gap-2 mb-2">
            <SearchInput
              placeholder="이름 (필수) *"
              value={searchName}
              onChange={setSearchName}
              onEnter={handleSearch}
            />
            <SearchInput
              placeholder="생년월일"
              value={searchBirthDate}
              onChange={setSearchBirthDate}
              type="date"
              onEnter={handleSearch}
            />
            <select
              value={searchGender}
              onChange={(e) => setSearchGender(e.target.value)}
              className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-400 transition-colors"
            >
              <option value="">성별 (전체)</option>
              <option value="M">남</option>
              <option value="F">여</option>
              <option value="OTHER">기타</option>
            </select>
            <SearchInput
              placeholder="전화번호"
              value={searchPhone}
              onChange={setSearchPhone}
              onEnter={handleSearch}
            />
          </div>
          <div className="flex justify-end gap-2">
            <button
              onClick={handleNewPatient}
              className="px-3 py-1.5 rounded text-xs bg-gray-700 hover:bg-gray-600 text-gray-200 transition-colors cursor-pointer"
            >
              신규 등록
            </button>
            <button
              onClick={handleSearch}
              disabled={isSearching || !searchName.trim()}
              className="px-4 py-1.5 rounded text-xs bg-blue-600 hover:bg-blue-500 text-white font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
            >
              {isSearching ? "검색 중..." : "검색"}
            </button>
          </div>
        </Card>

        {/* ── 검색 결과 ── */}
        {hasSearched && !isSearching && (
          searchResults.length > 0 ? (
            <Card title={`검색 결과 ${searchResults.length}명 — 해당 환자를 선택하세요`} className="shrink-0">
              <Table
                headers={["이름", "생년월일", "성별", "전화번호", "환자번호", "최종진료일", "선택"]}
                data={searchTableData}
              />
            </Card>
          ) : (
            <div className="shrink-0 flex items-center gap-4 rounded border border-yellow-600/30 bg-yellow-500/10 px-4 py-3 text-xs text-yellow-200">
              <span>검색 결과가 없습니다.</span>
              <button
                onClick={handleNewPatient}
                className="px-3 py-1 rounded bg-yellow-600/40 hover:bg-yellow-600/60 text-yellow-100 font-medium transition-colors cursor-pointer"
              >
                신규 환자로 등록
              </button>
            </div>
          )
        )}

        {/* ── 환자정보 폼 ── */}
        <Card
          title={isExisting ? "환자정보" : "환자정보 (신규 등록)"}
          className="shrink-0"
        >
          {/* 모드 배지 */}
          <div className="mb-3 flex items-center gap-2">
            {isExisting ? (
              <>
                <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-blue-600/30 text-blue-300 border border-blue-500/30">
                  기존 환자 접수
                </span>
                <span className="text-xs text-gray-400">
                  환자번호 P{String(selectedPatient!.patientId).padStart(5, "0")} · {selectedPatient!.name}
                </span>
                <button
                  onClick={() => { setMode("new"); setSelectedPatient(null); setName(""); setGender(""); setBirthDate(""); setPhone(""); }}
                  className="ml-auto text-[11px] text-gray-400 hover:text-gray-200 underline cursor-pointer"
                >
                  다시 검색
                </button>
              </>
            ) : (
              <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-green-600/30 text-green-300 border border-green-500/30">
                신규 환자 등록
              </span>
            )}
          </div>

          <div className="grid grid-cols-2 gap-x-4 gap-y-2.5">
            {/* Row 1 */}
            <MockField
              label="환자번호"
              value={isExisting && selectedPatient
                ? `P${String(selectedPatient.patientId).padStart(5, "0")}`
                : "자동 생성"}
            />
            <MockField label="최종환자번호" value="—" />

            {/* Row 2 */}
            <Input
              label="성명 *"
              placeholder="성명을 입력하세요"
              value={name}
              onChange={setName}
              disabled={formDisabled}
            />
            <SelectField
              label="성별"
              value={gender}
              onChange={setGender}
              disabled={formDisabled}
              options={[
                { value: "", label: "선택" },
                { value: "M", label: "남" },
                { value: "F", label: "여" },
                { value: "OTHER", label: "기타" },
              ]}
            />

            {/* Row 3 */}
            <Input
              label="생년월일"
              type="date"
              value={birthDate}
              onChange={setBirthDate}
              disabled={formDisabled}
            />
            <MockField label="전진료일" value={selectedPatient?.lastVisitDate ?? "—"} />

            {/* Row 4 */}
            <Input
              label="휴대폰번호"
              placeholder="010-0000-0000"
              value={phone}
              onChange={setPhone}
              disabled={formDisabled}
            />
            <MockField label="주민번호" value="●●●●●●-●●●●●●●" />

            {/* Row 5 */}
            <MockField label="E-Mail" value="—" />
            <MockField label="보호자연락처" value="—" />

            {/* Row 6 */}
            <div className="col-span-2">
              <MockField label="주소" value="—" />
            </div>
          </div>
        </Card>

        {/* ── 특이사항 (접수 메모) ── */}
        <Card title="특이사항" className="shrink-0">
          <textarea
            value={memo}
            onChange={(e) => setMemo(e.target.value)}
            disabled={isSubmitting}
            className="w-full h-[80px] bg-transparent text-xs text-white outline-none resize-none placeholder-gray-500 disabled:opacity-60"
            placeholder="이번 방문 특이사항 및 접수 메모를 입력하세요"
          />
        </Card>

        {/* ── 보험급여 ── */}
        <Card title="보험급여" className="shrink-0">
          <MockField label="증번호" value="—" />
        </Card>

        {/* ── 버튼 ── */}
        <div className="flex justify-end gap-2 pb-1 shrink-0">
          <Button type="secondary" onClick={handleReset} disabled={isSubmitting}>
            초기화
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit}>
            {isSubmitting
              ? "접수 중..."
              : isExisting
                ? "기존 환자 접수"
                : "신규 환자 접수"}
          </Button>
        </div>
      </section>

      {/* ── 우측 컬럼 (진료현황) ── */}
      <section className="flex-1 flex flex-col overflow-y-auto min-w-0">
        <Card title="진료 현황" className="flex-1">
          <div className="flex border-b border-gray-700 mb-3 pb-1 gap-2">
            {(["대기", "완료"] as const).map((tab) => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`px-4 py-1.5 text-xs font-medium rounded transition-colors cursor-pointer ${
                  activeTab === tab
                    ? "bg-blue-600 text-white font-bold"
                    : "bg-gray-800 text-gray-400 hover:bg-gray-700"
                }`}
              >
                {tab === "대기" ? "진료대기" : "진료완료"}
              </button>
            ))}
            <button
              onClick={loadVisits}
              disabled={isListLoading}
              className="ml-auto px-3 py-1.5 text-xs bg-gray-800 hover:bg-gray-700 text-gray-400 rounded transition-colors disabled:opacity-50"
            >
              새로고침
            </button>
          </div>

          {isListLoading && (
            <p className="mb-3 text-xs text-gray-400">목록을 불러오는 중...</p>
          )}

          <Table
            headers={["순번", "접수번호", "환자번호", "이름", "접수시간", "상태"]}
            data={visitTableData}
          />
        </Card>
      </section>

    </div>
  );
}
