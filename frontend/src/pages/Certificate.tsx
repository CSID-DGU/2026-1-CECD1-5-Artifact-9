import { Card } from "../components/Card";
import { Button } from "../components/Button";

const CERTIFICATE_TYPES = [
  { label: "진단서", description: "진단명 및 향후 치료 계획을 포함한 공식 진단서" },
  { label: "소견서", description: "의사 소견 및 치료 경과 기록" },
  { label: "진료확인서", description: "진료 사실을 확인하는 확인서" },
  { label: "처방전", description: "약품 처방 내역" },
  { label: "입원확인서", description: "입원 사실 확인서" },
  { label: "상해진단서", description: "상해 정도 및 치료 기간 진단서" },
];

export default function Certificate() {
  return (
    <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">
      {/* Left: Certificate type selection */}
      <section className="w-[280px] flex flex-col shrink-0 gap-[8px]">
        <Card title="증명서 종류">
          <div className="flex flex-col gap-2">
            {CERTIFICATE_TYPES.map((c) => (
              <button
                key={c.label}
                disabled
                className="w-full text-left px-3 py-2.5 rounded border border-gray-700 bg-side-bg hover:border-gray-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <div className="text-xs text-white font-medium">{c.label}</div>
                <div className="text-[10px] text-gray-400 mt-0.5">{c.description}</div>
              </button>
            ))}
          </div>
        </Card>
      </section>

      {/* Center: Preview area */}
      <section className="flex-1 flex flex-col gap-[8px]">
        <Card title="미리보기" className="flex-1">
          <div className="flex flex-col items-center justify-center h-full gap-4 py-10">
            <div className="w-16 h-16 rounded-full bg-gray-700/50 flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5" className="text-gray-500">
                <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
              </svg>
            </div>
            <div className="text-center">
              <p className="text-sm text-gray-400">증명서 기능 준비중</p>
              <p className="text-xs text-gray-500 mt-1">소견서 및 각종 증명서 발급 기능이 추가될 예정입니다</p>
            </div>
          </div>
        </Card>
      </section>

      {/* Right: Actions */}
      <section className="w-[200px] flex flex-col shrink-0 gap-[8px]">
        <Card title="발급">
          <div className="flex flex-col gap-2">
            <Button disabled>미리보기</Button>
            <Button disabled>인쇄</Button>
            <Button type="secondary" disabled>PDF 저장</Button>
          </div>
          <p className="text-[10px] text-gray-500 mt-3 text-center">추후 구현 예정</p>
        </Card>
      </section>
    </div>
  );
}
