import { useState } from "react";
import { Card } from "../components/Card"
import { Table } from "../components/Table";
import { usePatient } from "../hooks/usePatient";

export default function Clinic() {
  const { patients, toggleStatus } = usePatient();
  const [activeTab, setActiveTab] = useState<"대기" | "완료">("대기");

  // 진료 중인 환자 정보(하드코딩)
  const currentPatientInfo = [
    ["이름", <span key="name" className="font-semibold">홍길동</span>],
    ["번호", "P00001"],
    ["나이", "35세"],
    ["성별", "남"],
    ["이전 진료일", "2026-04-15"],
    ["환자 연락처", "010-1234-5678"],
    ["보호자 연락처", "010-9876-5432"]
  ];

  const centerTableData = patients
    .filter(p => p.status === (activeTab === "대기" ? "대기" : "완료"))
    .map((p, idx) => [
      idx + 1,
      p.id,
      p.name,
      p.time,
      <span key={`status-${p.id}`} className={`px-2 py-0.5 rounded text-[10px] ${p.status === "대기" ? "bg-orange-500/20 text-orange-400" : "bg-green-500/20 text-green-400"}`}>
        {p.status}
      </span>,
      <button 
        key={`btn-${p.id}`}
        onClick={() => toggleStatus(p.id)} 
        className="px-2 py-0.5 bg-gray-800 hover:bg-gray-700 border border-gray-600 text-[10px] rounded cursor-pointer transition-colors"
      >
        상태전환
      </button>
    ]);

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
              <span className="text-[11px] text-green-400 font-medium">진료중</span>
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

          {/* 환자 테이블 */}
          <Table 
            headers={["순번", "환자번호", "성명", "시간", "현재상태", "액션"]} 
            data={centerTableData} 
          />
        </Card>
        </section>

        {/* Right Column */}
        <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
          <Card>
            <div className="mt-2 border-t border-gray-700 pt-2">
            </div>
          </Card>

          <Card title="진료정보" className="flex-1">
            <div className="grid grid-cols-2 gap-x-6 gap-y-3">
            </div>
          </Card>
        </section>

      </div>
  );
}
