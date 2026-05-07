import React, { useState } from "react";
import { Input } from "./components/Input";
import { Card } from "./components/Card";
import { Tabs } from "./components/Tab";
import { Table } from "./components/Table";

export default function App() {
  const [tab, setTab] = useState("진료대기");

  return (
    <div className="min-h-screen bg-gray-900 text-white p-6 flex gap-6 font-sans">
      {/* 사이드바 영역 */}
      <aside className="w-30 bg-gray-800 rounded-xl p-4 text-gray-400 text-sm">
        Sidebar
      </aside>

      {/* 중앙 메인 콘텐츠 */}
      <main className="flex-1 flex flex-col gap-4">
        <Card title="환자 정보">
          <div className="grid grid-cols-2 gap-4">
            <Input label="이름" placeholder="이름을 입력하세요" />
            <Input label="주민번호" placeholder="[RRN Omitted]" />
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