import React, { useState } from "react";
import { Input } from "../components/Input";
import { Card } from "../components/Card";
import { Tab } from "../components/Tab";
import { Table } from "../components/Table";
import { Button } from "../components/Button";

export default function Reception() {
  const [tab, setTab] = useState("진료대기");

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
        <section className="flex-1 flex flex-col gap-[8px] overflow-y-auto">
          <Card>
            <Tab
              tabs={["진료대기", "예약대기"]}
              active={tab}
              setActive={setTab}
            />
            <div className="mt-2 border-t border-gray-700 pt-2">
              <Table
                headers={["순번", "환자명", "상태"]}
                data={[
                  ["1", "홍길동", "대기"],
                  ["2", "이순신", "진료중"],
                  ["3", "강감찬", "대기"],
                ]}
              />
            </div>
          </Card>

          <Card title="진료정보" className="flex-1">
            <div className="grid grid-cols-2 gap-x-6 gap-y-3">
              <Input label="진료과목" placeholder="내과" />
              <Input label="진료의사" placeholder="홍길동" />
    
              <Input label="초/재진" placeholder="초진" />
              <Input label="접수시간" placeholder="09:00" />
    
              <Input label="공단검진구분" placeholder="일반검진" />
              <Input label="내원사유" placeholder="감기 증상" />
    
              <Input label="예외환자코드" placeholder="해당 없음" />
              <Input label="내원경로" placeholder="외래" />
    
              <Input label="주야공휴" placeholder="주간" />
              <Input label="진료유형" placeholder="건강보험" />
            </div>
          </Card>
          <Card title="기초임상정보" className="h-[200px]">
            <Table
                headers={["입력일시", "체온", "키", "몸무게", "BMI"]}
                data={[
                  ["1", "0", "0", "0", "0"],
                ]}
              />
          </Card>
        </section>

      </div>
  );
}