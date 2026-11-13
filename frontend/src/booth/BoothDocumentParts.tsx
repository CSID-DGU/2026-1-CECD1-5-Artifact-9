import { BOOTH_DISCLAIMER } from "./boothMode";
import { ZODIAC_ANIMALS, type BoothVisitor } from "./fortune";

/**
 * 운세 소견서의 인적사항 자리. (부스 전용, 설명은 ./boothMode.ts)
 *
 * 접수는 운영진 이름으로 했으므로 평소 표(성명·주민등록번호·생년월일·연락처)를 그대로 찍으면 운영진 개인정보가
 * 방문객 손에 들려 나간다. 그 표 대신 방문객 이름을 크게 찍는다.
 * visitor 가 null 이면(발급 뒤 새로고침해 이름을 잃은 경우) 이름 자리를 빈 줄로 두어 손으로 쓰게 한다.
 * 흑백 프린터 기준이라 색 없이 검정 선과 글자 크기로만 강조한다.
 */
export function BoothVisitorHeadline({ visitor }: { visitor: BoothVisitor | null }) {
  // 외국 이름처럼 긴 이름은 글자를 줄여 한 줄에 담는다.
  const long = (visitor?.name.length ?? 0) > 6;
  return (
    <section className="border-2 border-black px-4 py-3 text-center">
      <div className="text-[11px] tracking-[0.4em]">오늘의 주인공</div>
      <div className="mt-1 flex items-end justify-center gap-2">
        {visitor ? (
          <span
            className={`font-bold leading-tight break-all ${
              long ? "text-[26px]" : "pl-[0.2em] text-[38px] tracking-[0.2em]"
            }`}
          >
            {visitor.name}
          </span>
        ) : (
          <span className="inline-block h-[12mm] w-[60mm] border-b border-black" />
        )}
        <span className="pb-1.5 text-[18px]">님</span>
      </div>
      {visitor && (
        <div className="mt-1 text-[12px]">
          {visitor.age}세 · {visitor.birthYear}년생 {ZODIAC_ANIMALS[visitor.animal]}띠
        </div>
      )}
    </section>
  );
}

/** 인쇄물 맨 아래 안내 — 진짜 의료 문서처럼 보이는 종이가 밖으로 나가므로 반드시 찍는다. */
export function BoothDisclaimer() {
  return (
    <p className="mt-5 border-t border-black pt-1.5 text-center text-[10px] leading-relaxed">
      {BOOTH_DISCLAIMER}
    </p>
  );
}
