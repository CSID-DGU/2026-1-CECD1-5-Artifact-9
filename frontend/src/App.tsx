import React from "react";

//type
type BtnProps = {
  children : React.ReactNode;
  type?: "primary"|"secondary";
};
type InputProps = {
  label : string;
  placeholder : string;
};
type CardProps = {
  title : string;
  children : React.ReactNode;
};
type TabsProps = {
  tabs: string[];
  active: string;
  setActive: (tab: string) => void;
};
type TableProps = {
  headers: string[];
  data: (string | number)[][];
};

// Basic Button
export const Button = ({ children, type = "primary" }:BtnProps) => {
  const base = "px-4 py-2 rounded-lg text-sm";
  const styles = {
    primary: "bg-blue-500 text-white",
    secondary: "border border-gray-400 text-gray-200",
  };
  return <button className={`${base} ${styles[type]}`}>{children}</button>;
};

// Input Component
export const Input = ({ label, placeholder }:InputProps) => {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-xs text-gray-400">{label}</label>
      <input
        placeholder={placeholder}
        className="px-3 py-2 rounded-lg bg-gray-800 border border-gray-600 text-sm"
      />
    </div>
  );
};

// Card Component
export const Card = ({ title, children }:CardProps) => {
  return (
    <div className="bg-gray-800 rounded-xl p-4 flex flex-col gap-3">
      {title && <div className="text-sm font-semibold">{title}</div>}
      {children}
    </div>
  );
};

// Tab Component
export const Tabs = ({ tabs, active, setActive }:TabsProps) => {
  return (
    <div className="flex gap-2">
      {tabs.map((tab) => (
        <button
          key={tab}
          onClick={() => setActive(tab)}
          className={`px-3 py-1 rounded-lg text-sm ${
            active === tab ? "bg-blue-500 text-white" : "text-gray-400"
          }`}
        >
          {tab}
        </button>
      ))}
    </div>
  );
};

// Table Component
export const Table = ({ headers, data }:TableProps) => {
  return (
    <div className="flex flex-col">
      <div className="flex border-b border-gray-600">
        {headers.map((h) => (
          <div key={h} className="flex-1 p-2 text-xs text-gray-400">
            {h}
          </div>
        ))}
      </div>
      {data.map((row, i) => (
        <div key={i} className="flex border-b border-gray-700">
          {row.map((cell, j) => (
            <div key={j} className="flex-1 p-2 text-sm">
              {cell}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
};

// Main Layout Example
export default function App() {
  const [tab, setTab] = React.useState("진료대기");

  return (
    <div className="min-h-screen bg-gray-900 text-white p-6 flex gap-6">
      {/* Left */}
      <div className="w-30 bg-gray-800 rounded-xl p-4">Sidebar</div>

      {/* Center */}
      <div className="flex-1 flex flex-col gap-4">
        <Card title="환자 정보">
          <div className="grid grid-cols-2 gap-4">
            <Input label="이름" placeholder="입력하세요" />
            <Input label="주민번호" placeholder="입력하세요" />
          </div>
        </Card>
      </div>

      {/* Right */}
      <div className="w-80 flex flex-col gap-4">
        <Card title="table">
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
      </div>
    </div>
  );
}