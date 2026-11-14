import { Button } from "../components/Button";
import {
  MAX_VISITOR_NAME,
  ZODIAC_ANIMALS,
  drawFortuneCookie,
  estimateBirthYear,
  formatFortuneOpinion,
  nearestYearOf,
  parseAge,
  toBoothVisitor,
  zodiacOf,
  type BoothInput,
} from "./fortune";

const INPUT_CLASS =
  "w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-white focus:outline-none focus:border-blue-500 disabled:opacity-50";

/**
 * 소견서 작성 칸 맨 위에 붙는 부스 입력 칸. (부스 전용, 설명은 ./boothMode.ts)
 *
 * 입력값(value)은 증명서 화면이 들고 있다 — 발급한 문서에 어느 방문객 이름을 찍을지 화면이 알아야 하기 때문이다.
 * 여기서는 그 값을 고치고(onChange), 소견 칸에 넣을 글을 만들어 넘긴다(onFill).
 */
export function BoothFortunePanel({
  value,
  onChange,
  onFill,
}: {
  value: BoothInput;
  onChange: (next: BoothInput) => void;
  onFill: (opinion: string) => void;
}) {
  const visitor = toBoothVisitor(value);

  // 이름이 아직 없어도 나이만으로 띠를 먼저 보여 준다.
  const age = parseAge(value.age);
  const estimate = age === null ? null : estimateBirthYear(age);
  const animal = estimate === null ? null : (value.animal ?? zodiacOf(estimate));
  const birthYear = estimate === null || animal === null ? null : nearestYearOf(animal, estimate);

  /** 운세를 이미 넣었다면 이름·나이·띠를 고칠 때 소견 글도 다시 만든다 — 입력과 어긋난 운세가 찍히지 않게. */
  function edit(next: BoothInput) {
    onChange(next);
    const nextVisitor = toBoothVisitor(next);
    if (next.cookie && nextVisitor) onFill(formatFortuneOpinion(nextVisitor, next.cookie));
  }

  /** 처음이면 운세를 넣고, 이미 넣었으면 포춘쿠키만 새로 뽑는다(띠 운세는 같은 날 같은 띠면 그대로다). */
  function draw() {
    if (!visitor) return;
    const cookie = drawFortuneCookie(value.cookie);
    onChange({ ...value, cookie });
    onFill(formatFortuneOpinion(visitor, cookie));
  }

  return (
    <div className="flex flex-col gap-2 rounded border border-amber-500/50 bg-amber-500/5 p-2.5">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-medium text-amber-200">부스 운세 소견서</span>
        <span className="rounded bg-amber-500/20 px-1.5 py-px text-[9px] text-amber-300">
          부스 모드
        </span>
      </div>

      <label className="flex flex-col gap-1">
        <span className="text-[11px] text-gray-400">방문객 이름</span>
        <input
          value={value.name}
          maxLength={MAX_VISITOR_NAME}
          onChange={(e) => edit({ ...value, name: e.target.value })}
          placeholder="인쇄물에 크게 찍힐 이름"
          className={`${INPUT_CLASS} text-sm`}
        />
      </label>

      <div className="flex gap-2">
        <label className="flex w-[72px] shrink-0 flex-col gap-1">
          <span className="text-[11px] text-gray-400">나이</span>
          <input
            value={value.age}
            inputMode="numeric"
            maxLength={3}
            // 나이를 바꾸면 직접 고른 띠는 버리고 다시 나이를 따른다.
            onChange={(e) => edit({ ...value, age: e.target.value.replace(/\D/g, ""), animal: null })}
            placeholder="세"
            className={`${INPUT_CLASS} text-xs`}
          />
        </label>
        <label className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="text-[11px] text-gray-400">띠</span>
          <select
            value={animal ?? ""}
            disabled={animal === null}
            onChange={(e) => edit({ ...value, animal: Number(e.target.value) })}
            className={`${INPUT_CLASS} text-xs`}
          >
            {animal === null && <option value="">나이를 먼저 넣으세요</option>}
            {ZODIAC_ANIMALS.map((name, i) => (
              <option key={name} value={i}>
                {name}띠
              </option>
            ))}
          </select>
        </label>
      </div>

      <p className="text-[10px] leading-relaxed text-gray-400">
        {birthYear !== null && animal !== null
          ? `${birthYear}년생 ${ZODIAC_ANIMALS[animal]}띠로 봅니다. 만 나이로 생일 전이거나 설 전 1~2월생이면 한 해 앞 띠일 수 있어요 — 다르면 띠를 고르세요.`
          : "나이를 넣으면 띠가 정해집니다."}
      </p>

      <Button onClick={draw} disabled={!visitor} className="px-2 py-1.5 text-[12px]">
        {value.cookie ? "포춘쿠키 다시 뽑기" : "오늘의 운세 넣기"}
      </Button>

      <p className="text-[10px] leading-relaxed text-gray-500">
        {visitor
          ? value.cookie
            ? "아래 소견 칸에 띠별 운세와 포춘쿠키가 들어갔습니다. 고쳐 써도 됩니다."
            : "버튼을 누르면 아래 소견 칸이 띠별 운세와 포춘쿠키로 채워집니다."
          : "부스 모드에서는 방문객 이름과 나이가 있어야 소견서를 발급할 수 있습니다."}
        <br />
        방문객 이름은 서버에 저장하지 않고 인쇄물에만 찍힙니다. 새로고침하면 이름 칸이 비니 발급 후
        바로 인쇄하세요.
      </p>
    </div>
  );
}
