import React, { useState } from "react";
import { Input } from "../components/Input";
import { Card } from "../components/Card";
import { Tab } from "../components/Tab";
import { Table } from "../components/Table";
import { Button } from "../components/Button";
import { usePatient } from "../hooks/usePatient";

export default function Reception() {
  const { patients, toggleStatus } = usePatient();
  
  // 테이블 상태
  const [activeTab, setActiveTab] = useState<"대기" | "완료">("대기");

  // 진료 대기 환자만 필터링
  const waitingData = patients.filter(p => p.status === "대기").map(p => [
    p.id, p.name, p.time,
    <button 
      onClick={() => toggleStatus(p.id)} 
      className="px-2 py-0.5 bg-blue-600 hover:bg-blue-500 text-[10px] rounded cursor-pointer transition-colors"
    >
      진료
    </button>
  ]);

  // 진료 완료 환자만 필터링
  const completedData = patients.filter(p => p.status === "완료").map(p => [
    p.id, p.name, p.time,
    <button 
      onClick={() => toggleStatus(p.id)} 
      className="px-2 py-0.5 bg-gray-700 hover:bg-gray-600 text-[10px] rounded text-gray-400 cursor-pointer transition-colors"
    >
      대기
    </button>
  ]);

  return (
      <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">
        
        {/* Left Column */}
        <section className="w-[300px] flex flex-col shrink-0">
          <Card title="특이사항" className="h-[100px]">
            <textarea 
              className="w-full h-full bg-transparent text-xs text-white outline-none resize-none"
              placeholder="특이사항을 입력하세요."
            />
          </Card>
        </section>

        {/* Center Column */}
        <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
          <Card title="환자정보">
            <div className="grid grid-cols-2 gap-x-4 gap-y-2">
              <Input label="환자번호" placeholder="P00001" />
              <Input label="최종환자번호" placeholder="자동 생성" />
              <Input label="성명" placeholder="성명을 입력하세요" />
              <Input label="전진료일" placeholder="YYYY-MM-DD" />
              <Input label="주민번호" placeholder="[RRN Omitted]" />
              <Input label="성별/나이" placeholder="남 / 30" />
              <Input label="휴대폰번호" placeholder="010-0000-0000" />
              <Input label="E-Mail" placeholder="example@mail.com" />
              <div className="col-span-2">
                <Input label="주소" placeholder="기본 주소 및 상세 주소" />
              </div>
              <Input label="보호자연락처" placeholder="010-0000-0000" />
            </div>
          </Card>
          
          <Card title="보험급여">
            <Input label="증번호" placeholder="보험 증번호 입력" />
          </Card>
        </section>

        {/* Right Column */}
        <section className="flex-1 flex flex-col overflow-y-auto">
        <Card title="진료 현황" className="flex-1">
          
          {/* 상태 버튼 영역 */}
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

          {/* 테이블 렌더링 */}
          {activeTab === "대기" ? (
            <Table headers={["번호", "성명", "접수시간", "변경"]} data={waitingData} />
          ) : (
            <Table headers={["번호", "성명", "완료시간", "변경"]} data={completedData} />
          )}

        </Card>
      </section>

      </div>
  );
}