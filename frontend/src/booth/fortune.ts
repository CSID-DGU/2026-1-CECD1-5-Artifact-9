/**
 * 부스 체험용 "오늘의 운세" — 띠별 운세와 포춘쿠키. (부스 전용, 설명은 ./boothMode.ts)
 *
 * 방문객에게서 받는 것은 이름과 나이뿐이다. 생년월일·태어난 시가 없으니 사주는 볼 수 없고, 나이로 띠를 정해
 * 신문 운세란처럼 보여 준다.
 *   - 띠 운세·총운: 같은 날 같은 띠면 누구나 같다(운세란이 원래 그렇다). 날마다 문구가 바뀐다.
 *   - "OO년생" 한 줄: 같은 날 같은 해에 태어났으면 같다.
 *   - 행운의 숫자·색·아이템: 이름까지 섞어 뽑는다. 같은 띠 친구끼리 와도 여기서 갈린다.
 *   - 포춘쿠키: 매번 무작위다.
 * 날짜·이름·나이가 같으면 몇 번을 다시 만들어도 포춘쿠키 말고는 같은 글이 나온다 — 고쳐 찍어도 운세가 바뀌지 않는다.
 *
 * 이 파일은 화면(React)에 기대지 않는 순수 함수와 문구만 둔다.
 */

export const ZODIAC_ANIMALS = [
  "쥐", "소", "호랑이", "토끼", "용", "뱀", "말", "양", "원숭이", "닭", "개", "돼지",
] as const;

/** 소견 글 첫 줄. 문서 화면이 "운세 소견서인가"를 이 머리말로 알아본다(CertificateDocumentView). */
export const FORTUNE_HEADING = "■ 오늘의 띠별 운세";

/** 방문객 이름 최대 길이. 인쇄물에 크게 찍히므로 너무 길면 줄이 넘친다. */
export const MAX_VISITOR_NAME = 20;

/** 부스 패널의 입력값 그대로. 나이는 입력 중인 문자열이다. */
export type BoothInput = {
  name: string;
  age: string;
  /** 직접 고른 띠(0=쥐 … 11=돼지). null 이면 나이로 계산한 띠를 따른다. */
  animal: number | null;
  /** 지금 소견 칸에 들어가 있는 포춘쿠키 문구. null 이면 아직 운세를 넣지 않았다. */
  cookie: string | null;
};

export const EMPTY_BOOTH_INPUT: BoothInput = { name: "", age: "", animal: null, cookie: null };

/**
 * 인쇄물에 찍히는 방문객. **서버로 보내지 않는다** — 화면 메모리에만 있다가 새로고침하면 사라진다.
 * 접수는 운영진 이름으로 하고 방문객 이름은 어디에도 저장하지 않는 것이 부스 운영 원칙이다(개인정보).
 */
export type BoothVisitor = {
  name: string;
  age: number;
  birthYear: number;
  animal: number;
};

/** 서기 4년이 쥐띠다(2020년도 쥐띠). 0=쥐 … 11=돼지. */
export function zodiacOf(year: number): number {
  return (((year - 4) % 12) + 12) % 12;
}

/** 나이 입력값 → 숫자. 1~120 의 정수가 아니면 null. */
export function parseAge(text: string): number | null {
  if (!/^\d{1,3}$/.test(text)) return null;
  const age = Number(text);
  return age >= 1 && age <= 120 ? age : null;
}

/**
 * 나이로 추정한 출생년도. 만 나이(올해 생일이 지남)와 연 나이는 둘 다 "올해 − 나이" 다.
 * 생일 전인 만 나이나, 설·입춘 전에 태어난 1~2월생은 한 해 앞 띠일 수 있다 — 그래서 화면에서 띠를 고칠 수 있게 둔다.
 */
export function estimateBirthYear(age: number, today: Date = new Date()): number {
  return today.getFullYear() - age;
}

/** 고른 띠에 해당하는 해 중 나이로 추정한 해에 가장 가까운 해. 거리가 같으면 먼저 태어난 쪽. */
export function nearestYearOf(animal: number, estimate: number): number {
  for (let d = 0; d <= 6; d++) {
    if (zodiacOf(estimate - d) === animal) return estimate - d;
    if (zodiacOf(estimate + d) === animal) return estimate + d;
  }
  return estimate;
}

/** 입력이 다 갖춰졌으면 방문객, 아니면 null(이름이 비었거나 나이가 이상하다). */
export function toBoothVisitor(input: BoothInput, today: Date = new Date()): BoothVisitor | null {
  const name = input.name.trim();
  const age = parseAge(input.age);
  if (!name || name.length > MAX_VISITOR_NAME || age === null) return null;
  const estimate = estimateBirthYear(age, today);
  const animal = input.animal ?? zodiacOf(estimate);
  return { name, age, animal, birthYear: nearestYearOf(animal, estimate) };
}

export type ZodiacFortune = {
  dateLabel: string;
  animalName: string;
  /** 총운 별 개수 3~5. 부스 기념품이니 나쁜 운세는 없다. */
  stars: number;
  message: string;
  yearLine: string;
  luckyNumber: number;
  luckyColor: string;
  luckyItem: string;
};

export function zodiacFortune(visitor: BoothVisitor, today: Date = new Date()): ZodiacFortune {
  const day = dayKey(today);
  const messages = ANIMAL_MESSAGES[visitor.animal];
  const personal = `${day}|${visitor.name}|${visitor.birthYear}`;
  return {
    dateLabel: `${today.getFullYear()}년 ${today.getMonth() + 1}월 ${today.getDate()}일 (${WEEKDAYS[today.getDay()]})`,
    animalName: ZODIAC_ANIMALS[visitor.animal],
    // 날마다 한 칸씩 돌린다 — 해시로 고르면 이틀 연속 같은 문구가 나올 수 있다.
    message: messages[(dayNumber(today) + visitor.animal) % messages.length],
    stars: 3 + (hash(`${day}|stars|${visitor.animal}`) % 3),
    yearLine: pick(YEAR_LINES, `${day}|year|${visitor.birthYear}`),
    luckyNumber: 1 + (hash(`${personal}|number`) % 99),
    luckyColor: pick(LUCKY_COLORS, `${personal}|color`),
    luckyItem: pick(LUCKY_ITEMS, `${personal}|item`),
  };
}

/** 포춘쿠키 한 장. 방금 나온 문구(previous)는 다시 나오지 않는다. */
export function drawFortuneCookie(
  previous: string | null = null,
  random: () => number = Math.random,
): string {
  const pool = previous ? FORTUNE_COOKIES.filter((c) => c !== previous) : FORTUNE_COOKIES;
  return pool[Math.floor(random() * pool.length)];
}

/**
 * 소견 칸에 들어갈 글.
 *
 * 흑백 프린터로 나가므로 색·이모지 대신 ■ ★ 같은 기호만 쓴다(한글 글꼴에 다 들어 있다).
 * 방문객 이름은 넣지 않는다 — 이 글은 증명서와 함께 서버에 저장되기 때문이다. 이름은 인쇄할 때 화면에서만 덧붙는다.
 * 띠·출생년도는 들어간다(이름 없이는 누구인지 알 수 없는 정보다).
 */
export function formatFortuneOpinion(
  visitor: BoothVisitor,
  cookie: string,
  today: Date = new Date(),
): string {
  const f = zodiacFortune(visitor, today);
  const shortYear = String(visitor.birthYear % 100).padStart(2, "0");
  return [
    `${FORTUNE_HEADING} · ${f.dateLabel}`,
    `${f.animalName}띠 (${visitor.birthYear}년생)   총운 ${"★".repeat(f.stars)}${"☆".repeat(5 - f.stars)}`,
    f.message,
    `${shortYear}년생: ${f.yearLine}`,
    `행운의 숫자 ${f.luckyNumber} · 행운의 색 ${f.luckyColor} · 행운의 아이템 ${f.luckyItem}`,
    "",
    "■ 포춘쿠키",
    `“${cookie}”`,
    "",
    // 문서 맨 아래에도 체험용 안내가 찍히지만, 부스가 끝나 평소 서식으로 열어 볼 때를 위해 글에도 한 줄 남긴다.
    "※ 재미로 보는 운세이며 의학적 소견이 아닙니다.",
  ].join("\n");
}

/** 저장된 소견 글이 이 기능으로 만든 운세인가. 운영진이 첫 줄을 지웠다면 평소 소견서로 본다. */
export function isFortuneOpinion(opinion: string | null | undefined): boolean {
  return opinion?.trimStart().startsWith(FORTUNE_HEADING) ?? false;
}

/* ------------------------------------------------------------------ */
/* 내부 도구                                                            */
/* ------------------------------------------------------------------ */

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"] as const;

/** 브라우저(부스 노트북) 시각 기준 날짜. 서버 시각을 쓰지 않는 것은 운세가 화면에서만 만들어지기 때문이다. */
function dayKey(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/** 1970-01-01 부터 센 날수(현지 날짜 기준). */
function dayNumber(d: Date): number {
  return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000);
}

/** 문자열 → 32비트 정수. 같은 입력이면 어느 기기에서든 같은 값이다(FNV-1a 뒤에 murmur3 마무리로 고르게 섞는다). */
function hash(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h >>> 0;
}

function pick<T>(items: readonly T[], seed: string): T {
  return items[hash(seed) % items.length];
}

/* ------------------------------------------------------------------ */
/* 문구 — 부스 기념품이라 전부 좋은 말만 둔다                             */
/* ------------------------------------------------------------------ */

/** 띠별 운세. 순서는 ZODIAC_ANIMALS 와 같다. 띠마다 그 동물의 성격을 빌려 썼다. */
const ANIMAL_MESSAGES: readonly (readonly string[])[] = [
  [
    "눈치 빠른 쥐처럼 작은 기회도 놓치지 않으니 재물이 차곡차곡 쌓인다.",
    "부지런히 움직인 만큼 곳간이 채워진다. 미뤄 둔 일을 오늘 끝내면 홀가분하다.",
    "영리한 판단이 빛나는 날이다. 망설이던 선택이 있다면 직감을 믿어라.",
    "작은 정보 하나가 큰 이득으로 이어진다. 주변의 이야기에 귀를 기울여라.",
    "꼼꼼함이 무기가 되는 날이다. 한 번 더 확인한 덕분에 실수를 피한다.",
  ],
  [
    "묵묵히 밭을 가는 소처럼 꾸준함이 결실을 맺는다. 오늘의 노력이 곧 보상으로 돌아온다.",
    "서두르지 않아도 된다. 한 걸음씩 나아가는 당신을 따라올 사람이 없다.",
    "든든한 버팀목이 되어 준 덕분에 주변의 신뢰가 한층 깊어진다.",
    "오래 붙잡고 있던 일이 드디어 마무리된다. 스스로를 칭찬해 주어라.",
    "우직한 고집이 오늘은 장점이 된다. 원칙을 지키면 좋은 결과가 따른다.",
  ],
  [
    "호랑이의 기세로 밀어붙이면 막혔던 일이 시원하게 뚫린다.",
    "앞장서서 이끄는 자리에 행운이 따른다. 망설이지 말고 먼저 손을 들어라.",
    "용기 있게 꺼낸 한마디가 상황을 바꾼다. 하고 싶은 말은 오늘 하라.",
    "큰 산도 단숨에 넘을 힘이 있는 날이다. 미뤄 둔 도전을 시작하기 좋다.",
    "당당한 태도가 사람을 끌어당긴다. 어깨를 펴면 운도 함께 펴진다.",
  ],
  [
    "재빠른 토끼처럼 순간의 판단이 좋은 기회를 붙잡는다.",
    "부드러운 말씨가 꼬인 관계를 풀어 준다. 먼저 웃으며 다가가라.",
    "깡충 뛰어오르듯 기분 좋은 소식이 연달아 들려온다.",
    "재치 있는 아이디어가 주목받는 날이다. 떠오른 생각은 바로 적어 두어라.",
    "가볍게 나선 길에서 반가운 인연을 만난다. 오늘 들어온 약속은 거절하지 마라.",
  ],
  [
    "구름을 타고 오르는 용처럼 운이 상승한다. 큰 그림을 그리기 좋은 날이다.",
    "여의주를 문 듯 원하던 것이 손에 들어온다. 자신감을 가져라.",
    "존재감이 빛나는 날이다. 맡은 일에서 좋은 평가를 받는다.",
    "높이 오를수록 주변을 살펴라. 함께한 사람 덕분에 더 멀리 간다.",
    "오래 품어 온 꿈에 날개가 돋는다. 오늘 내딛는 첫걸음이 중요하다.",
  ],
  [
    "지혜로운 뱀처럼 한발 앞서 내다보니 손해 볼 일이 없다.",
    "조용히 준비해 온 일이 빛을 보는 날이다. 때를 기다린 보람이 있다.",
    "직감이 유난히 잘 맞는다. 첫 느낌을 믿고 움직여라.",
    "허물을 벗듯 묵은 고민을 털어 내면 몸도 마음도 가벼워진다.",
    "말을 아끼고 귀를 열면 중요한 정보가 저절로 모인다.",
  ],
  [
    "올해의 주인공답게 어디서든 눈에 띈다. 달리는 말에 날개를 단 격이다.",
    "힘차게 내달리는 말처럼 추진력이 최고조다. 시작한 일은 끝까지 밀고 나가라.",
    "새로운 곳으로 나서면 운이 트인다. 가까운 산책도 좋은 기운을 준다.",
    "자유로운 발상이 막힌 문제를 푼다. 틀에 얽매이지 마라.",
    "함께 달리는 동료가 큰 힘이 된다. 고마운 마음을 표현하면 운이 배가된다.",
  ],
  [
    "온화한 양처럼 부드러운 태도가 사람을 모은다. 오늘은 인기운이 높다.",
    "다툼 대신 양보를 택하면 더 큰 것이 돌아온다.",
    "섬세한 감각이 빛나는 날이다. 꾸미고 만드는 일에서 칭찬을 받는다.",
    "포근한 휴식이 행운을 부른다. 무리하지 말고 컨디션을 챙겨라.",
    "따뜻한 말 한마디가 누군가의 하루를 바꾼다. 그 마음이 복으로 돌아온다.",
  ],
  [
    "재주 많은 원숭이처럼 어려운 문제를 재치 있게 풀어낸다.",
    "번뜩이는 아이디어가 쏟아지는 날이다. 회의나 발표에서 주목받는다.",
    "유쾌한 농담이 분위기를 살린다. 당신 곁으로 사람이 모인다.",
    "새로운 기술을 배우기 좋은 날이다. 손에 익는 속도가 놀랍다.",
    "나무를 옮겨 타듯 상황 판단이 빠르다. 계획을 바꿔도 결과가 좋다.",
  ],
  [
    "새벽을 여는 닭처럼 일찍 시작한 일이 술술 풀린다.",
    "꼼꼼한 계획이 빛을 발한다. 오늘 세운 계획은 그대로 이루어진다.",
    "당신의 한마디가 모두를 깨운다. 의견을 자신 있게 말하라.",
    "정리정돈이 행운을 부른다. 책상과 가방을 비우면 좋은 일이 들어온다.",
    "성실함이 드디어 인정받는다. 기다리던 연락이 온다.",
  ],
  [
    "의리 있는 개처럼 곁을 지킨 사람이 큰 힘이 되어 준다.",
    "믿음을 지킨 만큼 신뢰가 쌓인다. 약속을 지키는 것이 오늘의 행운이다.",
    "반가운 사람과 다시 만나는 날이다. 오랜 친구에게 연락해 보라.",
    "꼬리를 흔들 만큼 기분 좋은 일이 생긴다. 웃음이 끊이지 않는다.",
    "정직한 선택이 결국 이긴다. 돌아가는 것 같아도 바른길을 택하라.",
  ],
  [
    "복을 부르는 돼지답게 뜻밖의 선물이 들어온다.",
    "먹을 복이 넘치는 날이다. 맛있는 음식이 기분과 운을 함께 올린다.",
    "넉넉한 마음 씀씀이가 재물운을 키운다. 베푼 만큼 돌아온다.",
    "느긋한 여유가 행운을 부른다. 조급해하지 않아도 모든 일이 제자리를 찾는다.",
    "주변에 웃음을 나눠 주는 날이다. 당신 덕분에 모두가 즐겁다.",
  ],
];

/** "OO년생: …" 한 줄. */
const YEAR_LINES = [
  "미뤄 둔 연락을 먼저 하면 반가운 답이 온다.",
  "오후에 기다리던 소식이 들려온다.",
  "가까운 사람의 조언 속에 답이 있다.",
  "새로운 시작을 하기에 좋은 날이다.",
  "건강을 챙기면 운도 따라온다.",
  "평소보다 일찍 움직이면 이득이 있다.",
  "웃음이 많은 자리에 행운이 있다.",
  "생각만 하던 일을 실행에 옮겨라.",
  "작은 친절이 귀인을 부른다.",
  "서두르지 않아도 원하는 것을 얻는다.",
  "익숙한 길보다 새로운 길이 즐겁다.",
  "오늘 배운 것이 곧 쓸모를 찾는다.",
  "주변을 정리하면 마음도 가벼워진다.",
  "오랜 고민에 실마리가 보인다.",
  "칭찬을 아끼지 않으면 두 배로 돌아온다.",
  "잠깐의 휴식이 큰 능률을 만든다.",
  "사소한 오해가 시원하게 풀린다.",
  "기대하지 않았던 곳에서 기회가 온다.",
  "꾸준히 해 온 일이 인정받는다.",
  "지갑을 잘 챙기면 작은 횡재가 있다.",
] as const;

const LUCKY_COLORS = [
  "흰색", "검정", "회색", "남색", "하늘색", "초록", "연두", "노랑",
  "주황", "빨강", "분홍", "보라", "베이지", "갈색", "은색", "금색",
] as const;

/** 피부과 부스라 피부 관리 물건을 몇 개 섞었다. */
const LUCKY_ITEMS = [
  "선크림", "수분크림", "립밤", "손수건", "텀블러", "볼펜", "이어폰", "우산", "손거울",
  "운동화", "머리끈", "책갈피", "보조배터리", "사탕", "모자", "포스트잇", "에코백", "목도리",
] as const;

/** 포춘쿠키. 일상·피부 관리·개발 농담을 섞었다. 줄을 더하거나 빼도 다른 코드는 손댈 필요가 없다. */
export const FORTUNE_COOKIES: readonly string[] = [
  "오늘 미룬 일은 내일의 내가 해결해 준다. 하지만 오늘의 내가 하면 더 멋있다.",
  "물 한 잔이 피부를 살린다. 지금 바로 마셔라.",
  "기다리던 연락이 생각보다 빨리 온다.",
  "웃는 얼굴에 행운이 먼저 찾아온다.",
  "작은 친절 하나가 큰 인연이 되어 돌아온다.",
  "선크림을 바른 당신, 10년 뒤의 피부가 고마워한다.",
  "오늘 처음 만난 사람이 좋은 소식을 가져온다.",
  "잠을 푹 자면 고민의 절반이 사라진다.",
  "지금 하는 고민은 생각보다 쉽게 풀린다.",
  "산책 10분이 커피 한 잔보다 좋은 아이디어를 준다.",
  "당신의 노력은 이미 누군가 지켜보고 있다.",
  "가방 속 잊고 있던 물건이 오늘 쓸모를 찾는다.",
  "포기하려던 일, 딱 한 번만 더 해 보면 문이 열린다.",
  "오늘은 '좋아요'라고 말하면 재미있는 일이 생긴다.",
  "맛있는 점심이 오후의 운을 끌어올린다.",
  "책상을 정리하면 머릿속도 함께 정리된다.",
  "칭찬 한마디가 하루를 바꾼다. 먼저 건네 보라.",
  "생각지 못한 곳에서 작은 행운이 굴러온다.",
  "오늘 찍은 사진이 오래 기억될 추억이 된다.",
  "당신의 아이디어가 누군가에게 큰 영감이 된다.",
  "느려도 괜찮다. 방향이 맞으면 반드시 닿는다.",
  "오늘의 실수는 내일의 재미있는 이야깃거리가 된다.",
  "피부도 마음도 보습이 필요하다. 오늘은 스스로를 아껴 주어라.",
  "행운은 준비된 사람의 주머니로 들어간다.",
  "새로운 도전에 초록불이 켜졌다.",
  "이 쿠키를 뽑은 당신은 이미 운이 좋은 사람이다.",
  "AI도 미처 예측하지 못한 행운이 당신을 찾아간다.",
  "충분한 수면은 최고의 화장품이다.",
  "반가운 소식이 메시지로 도착한다.",
  "작심삼일도 열 번 하면 한 달이다.",
  "누군가에게 당신은 이미 최고의 선물이다.",
  "가볍게 시작한 일이 큰 성과로 이어진다.",
  "오늘 배운 한 가지가 오래 쓰일 무기가 된다.",
  "손을 깨끗이 씻는 것만으로도 운이 좋아진다. 건강이 최고의 복이다.",
  "길을 잃어도 괜찮다. 덕분에 새로운 지름길을 찾는다.",
  "에러 메시지 속에 정답이 숨어 있다. 끝까지 읽어 보라.",
  "저장(Ctrl+S)을 자주 누르는 사람에게 큰 화는 오지 않는다.",
  "좋아하는 노래가 우연히 흘러나와 기분이 좋아진다.",
  "따뜻한 차 한 잔이 행운을 데워 준다.",
  "당신의 꾸준함이 곧 빛을 본다.",
  "오늘 내린 결정은 생각보다 훌륭한 선택이다.",
  "건넨 미소가 두 배가 되어 돌아온다.",
  "오늘의 날씨: 맑음. 가끔 웃음 소나기.",
  "기분 좋은 우연이 연달아 겹치는 날이다.",
  "얼굴을 만지던 손을 멈추면 피부가 웃는다.",
  "이 부스에 들른 것처럼, 뜻밖의 재미가 계속 이어진다.",
  "세 번 웃으면 세 번 좋은 일이 생긴다.",
  "지금 떠오른 그 사람에게 안부를 전해 보라. 좋은 기운이 오간다.",
  "버그는 사라지고 테스트는 초록불이 된다.",
  "큰 행운은 작은 습관에서 시작된다. 오늘 하나만 실천해 보라.",
];
