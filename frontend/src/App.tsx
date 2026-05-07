import React, { useState } from "react";
import { Input } from "./components/Input";
import { Card } from "./components/Card";
import { Tabs } from "./components/Tab";
import { Table } from "./components/Table";

export default function App() {
  const [tab, setTab] = useState("진료대기");

  return (
    <div className="min-h-screen bg-main-bg text-white p-6 flex gap-6 font-sans">
      {/* 사이드바 영역 */}
      <aside className="w-30 bg-side-bg rounded-xl p-4 text-gray-400 text-sm">
        Sidebar
      </aside>

      {/* 중앙 메인 콘텐츠 */}
      <main className="flex-1 flex flex-col gap-4">
        <Card title="환자 정보">
          {/* 2컬럼 그리드로 배치 */}
          <div className="grid grid-cols-2 gap-x-8 gap-y-3">
            <Input label="환자번호" placeholder="P00001" />
            <Input label="최종환자번호" placeholder="자동 생성" />
            
            <Input label="성명" placeholder="성명을 입력하세요" />
            <Input label="전진료일" placeholder="YYYY-MM-DD" />
            
            {/* 주민번호: 개인정보 보호를 위해 Placeholder 처리 */}
            <Input label="주민번호" placeholder="******-*******" />
            <Input label="성별/나이" placeholder="남 / 30" />
            
            <Input label="휴대폰번호" placeholder="010-0000-0000" />
            <Input label="E-Mail" placeholder="example@mail.com" />
            
            {/* 주소는 길 수 있으므로 col-span-2를 사용하여 한 줄을 다 쓰게 할 수도 있습니다 */}
            <div className="col-span-2">
              <Input label="주소" placeholder="기본 주소 및 상세 주소" />
            </div>

            <Input label="보호자연락처" placeholder="010-0000-0000" />
          </div>
        </Card>
      </main>

      {/* 우측 패널 */}
      <aside className="w-80 flex flex-col gap-4">
        <Card>
          <Tabs
            tabs={["진료대기", "예약대기"]}
            active={tab}
            setActive={setTab}
          />
        </Card>

        <Card title="기초 정보">
          <Table
            headers={["항목", "값"]}
            data={[
              ["혈압", "120/80"],
              ["체온", "36.5"],
            ]}
          />
        </Card>
      </aside>
    </div>
  );
}