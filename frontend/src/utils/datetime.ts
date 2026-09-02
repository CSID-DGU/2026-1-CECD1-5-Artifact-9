/**
 * 환자에게 보여줄 날짜·시각 문자열.
 *
 * 이 함수만 별도 파일에 둔 이유가 있다. 원래는 PublicDocumentFrame.tsx 안에 있었는데,
 * 컴포넌트 파일이 컴포넌트가 아닌 값을 export 하면 Vite 의 Fast Refresh 가 그 모듈
 * 전체를 갱신 대상에서 빼버린다(react-refresh/only-export-components).
 *
 * 화면마다 흩어져 있는 같은 모양의 로컬 함수들(Lookup, Reception, Clinic 등)까지
 * 여기로 모으는 것은 이번 변경 범위를 넘어서므로 건드리지 않았다.
 */
export function formatDateTime(raw: string | null | undefined): string {
  if (!raw) return "-";
  const d = new Date(raw);
  // 서버가 준 값을 파싱하지 못하면 임의로 가공하지 말고 원문 그대로 보여준다.
  if (Number.isNaN(d.getTime())) return raw;
  return d.toLocaleString("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
