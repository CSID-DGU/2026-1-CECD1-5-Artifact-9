/**
 * 접수번호(V00056) ↔ 내원 ID(56).
 *
 * 화면·접수증·텔레그램 알림에는 "V + 내원 ID 다섯 자리"로 찍힌다. 검색할 때는 V 나 앞의 0 을 빼고
 * 뒤의 숫자만 넣어도(56, 0056, v56) 같은 내원이다 — 부스처럼 같은 이름의 접수가 하루에 여러 건 쌓이면
 * 이름보다 접수번호가 확실하다.
 */
export function formatVisitNo(visitId: number): string {
  return `V${String(visitId).padStart(5, "0")}`;
}

/**
 * 접수번호로 읽히면 내원 ID, 아니면 null.
 * 이름 검색과 한 칸을 같이 쓰는 화면이 "이건 접수번호인가"를 가르는 데도 쓴다 — 이름에는 숫자만 들어갈 일이 없다.
 */
export function parseVisitNo(input: string): number | null {
  const match = /^[vV]?(\d{1,9})$/.exec(input.replace(/\s+/g, ""));
  if (!match) return null;
  const visitId = Number(match[1]);
  return visitId > 0 ? visitId : null;
}
