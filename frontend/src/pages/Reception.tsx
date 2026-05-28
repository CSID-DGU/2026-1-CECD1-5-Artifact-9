import { useEffect, useState } from "react";
import { createPatient, getPatient } from "../api/patients";
import { createVisit, listVisits, type Visit, type VisitStatus } from "../api/visits";
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

function formatDateTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "요청 처리 중 오류가 발생했습니다.";
}

/** Input과 동일한 시각 스타일의 Select */
function SelectField({
  label,
  value,
  onChange,
  options,
  disabled = false,
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

/** 저장되지 않는 목업 전용 비활성 Input */
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

export default function Reception() {
  // 실제 백엔드 연동 필드
  const [name, setName] = useState("");
  const [gender, setGender] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [phone, setPhone] = useState("");
  const [memo, setMemo] = useState("");

  // 진료 현황 목록
  const [activeTab, setActiveTab] = useState<"대기" | "완료">("대기");
  const [receivedVisits, setReceivedVisits] = useState<Visit[]>([]);
  const [completedVisits, setCompletedVisits] = useState<Visit[]>([]);
  const [patientNameMap, setPatientNameMap] = useState<Map<number, string>>(new Map());
  const [isListLoading, setIsListLoading] = useState(false);

  // 접수 처리 상태
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [lastRegistered, setLastRegistered] = useState<{ patientId: number; visitId: number; patientName: string } | null>(null);

  async function loadVisits() {
    setIsListLoading(true);
    try {
      const [received, completed] = await Promise.all([
        listVisits("RECEIVED"),
        listVisits("COMPLETED"),
      ]);
      setReceivedVisits(received);
      setCompletedVisits(completed);

      // 환자 이름 일괄 조회
      const allVisits = [...received, ...completed];
      const uniqueIds = [...new Set(allVisits.map((v) => v.patientId))];
      const patients = await Promise.all(uniqueIds.map((id) => getPatient(id).catch(() => null)));
      const nameMap = new Map<number, string>();
      uniqueIds.forEach((id, i) => { if (patients[i]) nameMap.set(id, patients[i]!.name); });
      setPatientNameMap(nameMap);
    } catch {
      // 목록 로딩 실패는 조용히 처리
    } finally {
      setIsListLoading(false);
    }
  }

  useEffect(() => { loadVisits(); }, []);

  function handleReset() {
    setName("");
    setGender("");
    setBirthDate("");
    setPhone("");
    setMemo("");
    setMessage(null);
    setErrorMessage(null);
    setLastRegistered(null);
  }

  async function handleSubmit() {
    if (!name.trim()) {
      setErrorMessage("성명은 필수 항목입니다.");
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);
    setMessage(null);

    try {
      const patient = await createPatient({
        name: name.trim(),
        gender: (gender as "M" | "F" | "OTHER") || null,
        birthDate: birthDate || null,
        phone: phone.trim() || null,
        memo: memo.trim() || null,
      });

      const visit = await createVisit(patient.id);

      const patientNo = `P${String(patient.id).padStart(5, "0")}`;
      const visitNo = `V${String(visit.id).padStart(5, "0")}`;

      setLastRegistered({ patientId: patient.id, visitId: visit.id, patientName: patient.name });
      setMessage(`접수 완료 — 환자번호 ${patientNo} (${patient.name}) / 접수번호 ${visitNo}`);

      setName("");
      setGender("");
      setBirthDate("");
      setPhone("");
      setMemo("");

      setActiveTab("대기"); // 등록 후 진료대기 탭으로 자동 전환
      await loadVisits();
    } catch (error) {
      setErrorMessage(getErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  }

  const activeVisits = activeTab === "대기" ? receivedVisits : completedVisits;

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

  const tableData = activeVisits.map((visit, idx) => [
    idx + 1,
    `V${String(visit.id).padStart(5, "0")}`,
    `P${String(visit.patientId).padStart(5, "0")}`,
    patientNameMap.get(visit.patientId) ?? "-",
    formatDateTime(visit.visitDate),
    <span
      key={`status-${visit.id}`}
      className={`px-2 py-0.5 rounded text-[10px] ${STATUS_COLORS[visit.status]}`}
    >
      {STATUS_LABELS[visit.status]}
    </span>,
  ]);

  return (
    <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">

      {/* Left Column */}
      <section className="w-[300px] flex flex-col shrink-0 gap-[8px]">
        <Card title="특이사항" className="h-[130px] shrink-0">
          <textarea
            value={memo}
            onChange={(e) => setMemo(e.target.value)}
            className="w-full h-full bg-transparent text-xs text-white outline-none resize-none placeholder-gray-500"
            placeholder="특이사항 및 의료진 메모를 입력하세요"
          />
        </Card>

        {lastRegistered && (
          <Card title="최근 접수 정보" className="shrink-0">
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-3">
                <span className="text-[10px] text-gray-400 w-16 text-right">성명</span>
                <span className="text-sm text-white font-semibold">{lastRegistered.patientName}</span>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-[10px] text-gray-400 w-16 text-right">환자번호</span>
                <span className="font-mono text-sm text-blue-300 font-semibold">
                  P{String(lastRegistered.patientId).padStart(5, "0")}
                </span>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-[10px] text-gray-400 w-16 text-right">접수번호</span>
                <span className="font-mono text-sm text-blue-300 font-semibold">
                  V{String(lastRegistered.visitId).padStart(5, "0")}
                </span>
              </div>
              <p className="text-[10px] text-gray-400 mt-1">진료실에서 접수번호로 진료를 진행하세요.</p>
            </div>
          </Card>
        )}
      </section>

      {/* Center Column */}
      <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
        <Card title="환자정보">
          {errorMessage && (
            <p className="mb-3 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-xs text-red-200">
              {errorMessage}
            </p>
          )}
          {message && (
            <p className="mb-3 rounded border border-green-500/40 bg-green-500/10 px-3 py-2 text-xs text-green-100">
              {message}
            </p>
          )}

          <div className="grid grid-cols-2 gap-x-4 gap-y-2.5">
            {/* Row 1 */}
            <MockField
              label="환자번호"
              value={lastRegistered ? `P${String(lastRegistered.patientId).padStart(5, "0")} (${lastRegistered.patientName})` : "자동 생성"}
            />
            <MockField label="최종환자번호" value="—" />

            {/* Row 2 */}
            <Input
              label="성명 *"
              placeholder="성명을 입력하세요"
              value={name}
              onChange={setName}
            />
            <SelectField
              label="성별"
              value={gender}
              onChange={setGender}
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
            />
            <MockField label="전진료일" value="—" />

            {/* Row 4 */}
            <Input
              label="휴대폰번호"
              placeholder="010-0000-0000"
              value={phone}
              onChange={setPhone}
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

        <Card title="보험급여" className="shrink-0">
          <MockField label="증번호" value="—" />
        </Card>

        <div className="flex justify-end gap-2 pb-1 shrink-0">
          <Button type="secondary" onClick={handleReset} disabled={isSubmitting}>
            초기화
          </Button>
          <Button onClick={handleSubmit} disabled={isSubmitting || !name.trim()}>
            {isSubmitting ? "접수 중..." : "접수 등록"}
          </Button>
        </div>
      </section>

      {/* Right Column */}
      <section className="flex-1 flex flex-col overflow-y-auto">
        <Card title="진료 현황" className="flex-1">
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
            data={tableData}
          />
        </Card>
      </section>

    </div>
  );
}
