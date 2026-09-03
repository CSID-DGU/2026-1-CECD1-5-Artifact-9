import { useEffect, useState } from "react";
import QRCode from "react-qr-code";
import { getErrorMessage } from "../api/errors";
import { printTicket } from "../api/print";
import {
  createPatient,
  getPatient,
  searchPatientsForReception,
  type PatientSearchResult,
} from "../api/patients";
import {
  cancelVisit,
  createVisit,
  issueKioskToken,
  listVisits,
  type Visit,
  type VisitStatus,
} from "../api/visits";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Input } from "../components/Input";
import { Table } from "../components/Table";
import {
  buildKioskUrl,
  getCurrentKioskBaseUrl,
  getKioskBaseUrl,
  resetKioskBaseUrlToCurrent,
  setKioskBaseUrl,
} from "../utils/kioskUrl";

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

/** QR 카드에 표시할 접수 1건. 접수 직후 또는 진료현황의 [QR] 버튼으로 채워진다. */
type KioskReceipt = {
  visitId: number;
  kioskToken: string;
  patientName: string;
  patientNo: string;
  visitNo: string;
};

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
  const [cancellingId, setCancellingId]       = useState<number | null>(null);

  // 제출 상태
  const [isSubmitting, setIsSubmitting]   = useState(false);
  const [message, setMessage]             = useState<string | null>(null);
  const [errorMessage, setErrorMessage]   = useState<string | null>(null);

  // 키오스크 QR
  const [receipt, setReceipt]                 = useState<KioskReceipt | null>(null);
  const [kioskBase, setKioskBase]             = useState(() => getKioskBaseUrl()); // 저장된 값
  const [kioskBaseInput, setKioskBaseInput]   = useState(() => getKioskBaseUrl()); // 입력창

  const kioskUrl = receipt ? buildKioskUrl(receipt.kioskToken, kioskBase) : "";

  // 감열지 티켓 수동 재출력. 접수 시점에 백엔드가 이미 자동으로 한 장 뽑지만,
  // 프린터가 꺼져 있었거나 환자가 종이를 잃어버린 경우를 위해 버튼을 둔다.
  const [isPrintingTicket, setIsPrintingTicket] = useState(false);

  async function handlePrintTicket() {
    if (!receipt) return;
    setMessage(null);
    setErrorMessage(null);
    setIsPrintingTicket(true);
    try {
      // kioskBase 를 같이 넘긴다. 담당자가 아래 '키오스크 접속 주소' 를 바꿨다면
      // 화면 QR 은 이미 그 주소인데, 안 넘기면 종이만 기본 주소로 나간다.
      const outcome = await printTicket(receipt.visitId, kioskBase);
      // 프린터가 꺼져 있어도 서버는 200 을 준다 — ok 플래그로 갈라서 안내한다.
      if (outcome.ok) setMessage(`티켓을 출력했습니다 — ${receipt.visitNo}`);
      else setErrorMessage(`티켓 출력 실패 — ${outcome.detail}`);
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsPrintingTicket(false);
    }
  }

  function handleSaveKioskBase() {
    setKioskBaseUrl(kioskBaseInput);
    const resolved = getKioskBaseUrl();
    setKioskBase(resolved);
    setKioskBaseInput(resolved);
  }

  function handleUseCurrentKioskBase() {
    const current = resetKioskBaseUrlToCurrent();
    setKioskBase(current);
    setKioskBaseInput(current);
  }

  /** 진료현황 행의 [QR] — 접수 화면을 새로고침해 QR을 잃었을 때 복구용. 토큰이 없으면 지연 발급한다. */
  async function handleShowQr(visit: Visit) {
    setErrorMessage(null);
    try {
      const token = visit.kioskToken ?? (await issueKioskToken(visit.id)).kioskToken;
      if (!token) {
        setErrorMessage("키오스크 토큰을 발급하지 못했습니다.");
        return;
      }
      setReceipt({
        visitId:     visit.id,
        kioskToken:  token,
        patientName: patientNameMap.get(visit.patientId) ?? "-",
        patientNo:   `P${String(visit.patientId).padStart(5, "0")}`,
        visitNo:     `V${String(visit.id).padStart(5, "0")}`,
      });
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    }
  }

  /** 진료현황 대기 목록의 [삭제] — 잘못 접수한 건을 취소한다. RECEIVED 상태에서만 가능. */
  async function handleCancelVisit(visit: Visit) {
    const patientName = patientNameMap.get(visit.patientId) ?? "-";
    const visitNo = `V${String(visit.id).padStart(5, "0")}`;
    if (!window.confirm(`${visitNo} (${patientName}) 접수를 삭제할까요?`)) return;

    setErrorMessage(null);
    setMessage(null);
    setCancellingId(visit.id);
    try {
      await cancelVisit(visit.id);
      if (receipt?.visitId === visit.id) setReceipt(null);
      setMessage(`접수를 삭제했습니다 — ${visitNo}`);
      await loadVisits();
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setCancellingId(null);
    }
  }

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
    setReceipt(null);
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

      // 세 번째 인자가 접수증 QR 에 찍힐 주소가 된다 — 화면에 뜨는 QR 과 같은 값이어야 한다.
      const visit = await createVisit(patientId, memo.trim() || null, kioskBase);

      const patientNo = `P${String(patientId).padStart(5, "0")}`;
      const visitNo   = `V${String(visit.id).padStart(5, "0")}`;

      // 폼 초기화 (메시지는 유지)
      setSelectedPatient(null);
      setMode("new");
      setName(""); setGender(""); setBirthDate(""); setPhone(""); setMemo("");
      setSearchName(""); setSearchBirthDate(""); setSearchGender(""); setSearchPhone("");
      setSearchResults([]);
      setHasSearched(false);

      // QR 카드용 — 폼은 비웠지만 방금 접수한 환자 정보는 남겨둔다.
      // 응답에 토큰이 없으면(구버전 백엔드 등) 발급 API로 한 번 더 시도한다.
      setReceipt(null);
      try {
        const kioskToken = visit.kioskToken ?? (await issueKioskToken(visit.id)).kioskToken;
        if (kioskToken) {
          setReceipt({ visitId: visit.id, kioskToken, patientName, patientNo, visitNo });
        } else {
          setErrorMessage("접수는 완료됐지만 키오스크 토큰을 받지 못했습니다. 백엔드가 최신 버전인지 확인해 주세요.");
        }
      } catch (error) {
        setErrorMessage(`접수는 완료됐지만 QR 생성에 실패했습니다 — ${getErrorMessage(error)}`);
      }

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

  // 진료현황 테이블 데이터 — 대기 탭에만 QR 재발급 버튼을 붙인다
  const isWaitingTab = activeTab === "대기";
  const visitTableHeaders = isWaitingTab
    ? ["순번", "접수번호", "환자번호", "이름", "접수시간", "상태", "QR", "삭제"]
    : ["순번", "접수번호", "환자번호", "이름", "접수시간", "상태"];

  const visitTableData = activeVisits.map((visit, idx) => [
    idx + 1,
    `V${String(visit.id).padStart(5, "0")}`,
    `P${String(visit.patientId).padStart(5, "0")}`,
    patientNameMap.get(visit.patientId) ?? "-",
    formatDateTime(visit.visitDate),
    <span key={`s-${visit.id}`} className={`px-2 py-0.5 rounded text-[10px] ${STATUS_COLORS[visit.status]}`}>
      {STATUS_LABELS[visit.status]}
    </span>,
    ...(isWaitingTab
      ? [
          <button
            key={`qr-${visit.id}`}
            onClick={() => handleShowQr(visit)}
            className="px-2 py-0.5 rounded bg-gray-700 hover:bg-gray-600 text-white text-[11px] font-medium transition-colors cursor-pointer"
          >
            QR
          </button>,
          <button
            key={`del-${visit.id}`}
            onClick={() => handleCancelVisit(visit)}
            disabled={cancellingId === visit.id}
            className="px-2 py-0.5 rounded bg-red-700 hover:bg-red-600 text-white text-[11px] font-medium transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
          >
            {cancellingId === visit.id ? "삭제 중..." : "삭제"}
          </button>,
        ]
      : []),
  ]);

  // ── 렌더 ───────────────────────────────────────────────
  return (
    // h-full: 좌우 컬럼 높이를 뷰포트에 고정해 각 패널이 안쪽에서만 스크롤되게 한다
    <div className="h-full flex-1 p-[8px] flex gap-[8px] overflow-hidden">

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

      {/* ── 우측 컬럼 (진료현황 + 키오스크 QR) ── */}
      <section className="flex-1 flex flex-col gap-[8px] overflow-hidden min-w-0">
        <Card title="진료 현황" className="flex-1 min-h-0">
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
            headers={visitTableHeaders}
            data={visitTableData}
          />
        </Card>

        {/* ── 키오스크 QR (접수 직후 / 대기목록 [QR] 버튼) ── */}
        {receipt && (
          <Card title="키오스크 QR" className="shrink-0">
            <div className="flex gap-4">
              {/* 다크 테마라 QR 주변 여백(quiet zone)을 흰색으로 깔아야 인식된다 */}
              <div className="shrink-0 rounded bg-white p-3">
                <QRCode value={kioskUrl} size={180} />
              </div>

              <div className="flex min-w-0 max-w-lg flex-1 flex-col gap-2">
                <div>
                  <p className="text-sm font-semibold text-white">
                    {receipt.patientName}
                    <span className="ml-2 text-xs font-normal text-gray-400">
                      {receipt.visitNo} · {receipt.patientNo}
                    </span>
                  </p>
                  <p className="mt-1 text-[11px] text-gray-400">
                    태블릿 또는 QR 리더기로 스캔하면 이 환자의 예비분석 화면으로 이동합니다.
                  </p>
                </div>

                <p className="break-all rounded bg-side-bg px-2 py-1 font-mono text-[11px] text-blue-300">
                  {kioskUrl}
                </p>

                <div>
                  <label className="text-[11px] text-gray-400">키오스크 접속 주소</label>
                  <div className="mt-1 flex gap-2">
                    <input
                      type="text"
                      value={kioskBaseInput}
                      onChange={(e) => setKioskBaseInput(e.target.value)}
                      onKeyDown={(e) => e.key === "Enter" && handleSaveKioskBase()}
                      placeholder="http://192.168.0.12:3000"
                      className="min-w-0 flex-1 rounded border border-gray-600 bg-side-bg px-2 py-1 text-xs text-white placeholder-gray-500 transition-colors focus:border-blue-400 focus:outline-none"
                    />
                    <button
                      onClick={handleUseCurrentKioskBase}
                      className="shrink-0 rounded border border-gray-500 px-3 py-1 text-xs text-gray-300 transition-colors hover:bg-gray-800 cursor-pointer"
                    >
                      현재 주소
                    </button>
                    <button
                      onClick={handleSaveKioskBase}
                      className="shrink-0 rounded border border-gray-400 px-3 py-1 text-xs text-gray-200 transition-colors hover:bg-gray-800 cursor-pointer"
                    >
                      저장
                    </button>
                  </div>
                  <p className="mt-1 text-[11px] text-gray-500">
                    기본값은 현재 접속 주소({getCurrentKioskBaseUrl()})입니다. 다른 기기에서 접속해야 할 때만 Mac IP 등으로 저장하세요.
                  </p>
                </div>

                <div className="mt-auto flex justify-end gap-2">
                  <button
                    onClick={handlePrintTicket}
                    disabled={isPrintingTicket}
                    className="rounded border border-gray-400 px-3 py-1 text-xs text-gray-200 transition-colors hover:bg-gray-800 cursor-pointer disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {isPrintingTicket ? "출력 중…" : "티켓 인쇄"}
                  </button>
                  <button
                    onClick={() => setReceipt(null)}
                    className="rounded border border-gray-400 px-3 py-1 text-xs text-gray-200 transition-colors hover:bg-gray-800 cursor-pointer"
                  >
                    닫기
                  </button>
                </div>
              </div>
            </div>
          </Card>
        )}
      </section>

    </div>
  );
}
