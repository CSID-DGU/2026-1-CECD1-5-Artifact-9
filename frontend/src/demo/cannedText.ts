/**
 * 데모의 AI 문장(참고 소견·처방 코멘트·증명서 초안). 데모는 실제 LLM 을 부르지 않는다 — 질환별로 미리 써 둔
 * 문장을 쓰고, 화면의 모델 이름 자리에 DEMO_MODEL 을 띄워 실제 생성 결과가 아님을 밝힌다.
 */
export const DEMO_MODEL = "데모 응답";

/** 악성·전암으로 보는 코드 — 백엔드 disease.severity 가 HIGH·MEDIUM 인 것(LowConfidenceCaution). */
export const DANGEROUS_CODES = new Set(["mel", "bcc", "akiec"]);

type Candidate = { diseaseCode: string; diseaseNameKo: string; confidence: number };

/** 키오스크 'AI 참고 소견' — 환자에게 보이는 두 문장. */
export function kioskComment(topK: Candidate[], confidenceLevel: "low" | "normal"): string {
  const first = topK[0];
  if (!first) return "결과를 정리하지 못했습니다. 진료실에서 의료진과 함께 확인해 주세요.";
  if (first.diseaseCode === "normal") {
    return "촬영하신 사진에서는 뚜렷한 병변 소견이 보이지 않습니다.\n불편한 증상이 있다면 진료 때 의료진과 꼭 상담해 주세요.";
  }
  const second = topK[1];
  const names = confidenceLevel === "low" && second
    ? `${first.diseaseNameKo} 또는 ${second.diseaseNameKo} 등의`
    : first.diseaseNameKo;
  const tail = DANGEROUS_CODES.has(first.diseaseCode)
    ? "최근 크기·색·모양이 변했다면 진료 때 꼭 말씀해 주시고, 반드시 전문의의 대면 진료를 받으시기 바랍니다."
    : "정확한 상태 파악을 위해 반드시 의료진의 대면 진료를 받아보시기 바랍니다.";
  return `촬영하신 부위는 ${names} 가능성이 있는 것으로 보입니다.\n${tail}`;
}

type DiseaseLine = { kcdCode: string; kcdNameKr: string; isPrimary: boolean };

const COMMENT_BY_PREFIX: Array<[string[], [string, string]]> = [
  [["L70"], ["처방한 외용제를 저녁 세안 후 여드름 부위에 얇게 바르고, 병변을 손으로 짜지 않도록 안내합니다.",
             "자극감·건조가 심하면 바르는 간격을 늘리고, 4주 뒤 재진해 염증성 구진의 변화를 확인합니다."]],
  [["L71"], ["열·음주·자외선 등 악화 요인을 피하고 자외선 차단제를 매일 바르도록 안내합니다.",
             "홍조와 구진의 변화를 4주 뒤 재진에서 확인하고, 반응이 부족하면 경구 치료를 검토합니다."]],
  [["L21"], ["항진균 성분 외용제와 샴푸를 주 2~3회 사용하고, 유분이 많은 화장품은 줄이도록 안내합니다.",
             "재발이 잦은 질환이라 증상이 가라앉은 뒤에도 유지 요법을 이어 가도록 설명합니다."]],
  [["L20"], ["보습제를 하루 2회 이상 충분히 바르고, 처방 외용제는 병변 부위에만 정해진 기간 사용하도록 안내합니다.",
             "가려움으로 긁지 않도록 손톱을 짧게 하고, 2~4주 뒤 재진해 습진 범위를 확인합니다."]],
  [["L40"], ["처방 외용제를 판 모양 병변에 하루 1~2회 바르고, 인설을 억지로 떼지 않도록 안내합니다.",
             "4주 뒤 재진해 병변 범위를 비교하고, 넓게 퍼지면 광선치료나 전신 치료를 검토합니다."]],
  [["L50"], ["처방한 항히스타민제를 정해진 시간에 복용하고, 의심되는 음식·약물을 기록해 두도록 안내합니다.",
             "호흡곤란·입술 부종이 생기면 즉시 응급실을 방문하도록 설명합니다."]],
  [["L23", "L24", "L25", "L30"], ["원인으로 의심되는 물질과의 접촉을 피하고, 처방 외용제를 병변 부위에 짧은 기간만 사용하도록 안내합니다.",
                                  "2주 뒤에도 호전이 없거나 범위가 넓어지면 첩포 검사를 검토합니다."]],
  [["C43", "D03"], ["악성 흑색종 가능성을 배제할 수 없어 조직검사가 가능한 상급 의료기관으로 진료를 의뢰합니다.",
                    "병변을 자극하지 말고, 의뢰 기관 진료 전까지 크기·색 변화를 사진으로 기록해 두도록 안내합니다."]],
  [["C44", "D04"], ["피부암 가능성이 있어 조직검사와 외과적 치료가 가능한 상급 의료기관으로 진료를 의뢰합니다.",
                    "상처 부위는 처방 연고로 깨끗이 관리하고, 의뢰 기관 진료 전까지 자외선 노출을 줄이도록 안내합니다."]],
  [["L57"], ["전암성 병변 가능성이 있어 냉동치료 또는 조직검사를 검토하고, 자외선 차단을 철저히 하도록 안내합니다.",
             "치료 후 4주 뒤 재진해 병변 변화를 확인합니다."]],
  [["D22", "L82", "D23", "D18"], ["양성 병변으로 보이나 크기·색·모양 변화가 생기면 바로 내원하도록 안내합니다.",
                                  "6~12개월 간격으로 사진을 찍어 변화를 비교해 보도록 설명합니다."]],
];

/** 진료실 'AI 처방 코멘트' — 주상병 코드 앞자리로 고른다. */
export function prescriptionComment(diseases: DiseaseLine[]): { line1: string; line2: string } {
  const primary = diseases.find((d) => d.isPrimary) ?? diseases[0];
  const code = primary?.kcdCode ?? "";
  const hit = COMMENT_BY_PREFIX.find(([prefixes]) => prefixes.some((p) => code.startsWith(p)));
  const [line1, line2] = hit ? hit[1] : [
    "처방 약제를 지시대로 사용하고, 병변 부위를 자극하지 않도록 안내합니다.",
    "증상이 지속되거나 악화되면 2~4주 안에 재진하도록 권합니다.",
  ];
  return { line1, line2 };
}

function koreanDate(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return `${y}년 ${m}월 ${d}일`;
}

function plusDays(iso: string, days: number): string {
  const d = new Date(`${iso.slice(0, 10)}T00:00:00`);
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** 증명서 'AI 초안' — 처방에 저장된 상병·약품과 내원일로 채운다. */
export function certificateDraft(
  type: string,
  facts: { visitDate: string; diseases: string[]; drugs: string[]; referralTo?: string | null },
): { opinion: string | null; treatmentPlan: string | null; referralReason: string | null } {
  const date = koreanDate(facts.visitDate);
  const names = facts.diseases.length ? facts.diseases.join(", ") : "피부 병변";
  const drugs = facts.drugs.length ? `${facts.drugs.join(", ")}을(를) 처방하였으며, ` : "";
  const revisit = koreanDate(plusDays(facts.visitDate, 28));
  switch (type) {
    case "DIAGNOSIS":
      return {
        opinion: `${date} 내원하여 시행한 진찰상 ${names} 소견으로 진단함. 병변의 임상 양상과 AI 보조 분석 결과를 함께 참고하였음.`,
        treatmentPlan: `${drugs}약 4주간 경과를 관찰한 뒤 ${revisit} 재진하여 치료 반응을 확인할 예정임.`,
        referralReason: null,
      };
    case "MEDICAL_OPINION":
      return {
        opinion: `상기 환자는 ${date} 본원에 내원하여 ${names} 소견을 보였으며, 처방 치료와 함께 경과 관찰 중임. 일상생활에 큰 지장은 없으나 병변 부위 자극을 피하고 정기적인 진료가 필요함.`,
        treatmentPlan: null,
        referralReason: null,
      };
    case "REFERRAL":
      return {
        opinion: `${date} 내원 시 ${names} 소견이 관찰되었고 AI 보조 분석에서도 같은 가능성이 제시됨. 귀원에서의 확진과 치료를 부탁드립니다.`,
        treatmentPlan: null,
        referralReason: `${names} 의심 소견으로 조직검사 등 정밀 검사와 전문 치료가 필요하여 의뢰함.`,
      };
    default:
      return { opinion: null, treatmentPlan: null, referralReason: null };
  }
}
