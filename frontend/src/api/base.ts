/**
 * API 서버 주소 — 서버 경로(`/api/...`)를 실제로 부를 주소로 바꾸는 곳은 여기 하나다.
 *
 * 화면과 API 가 같은 출처면(로컬 Vite proxy, 도커 nginx) VITE_API_BASE_URL 을 비워 두고 `/api/...` 를 그대로 부른다.
 * 화면은 Vercel, API 는 ALB 뒤(예: https://api.example.com)에 따로 두면 그 출처를 넣는다. 그러면 `/api/` 로 시작하는
 * 경로에만 앞에 붙는다. 화면의 정적 파일(`/hospital-seal.svg`, 데모의 `/demo/...`)과 blob: 주소는 그대로 둔다.
 *
 * 출처가 다르면 브라우저가 CORS 를 확인한다 — 백엔드 CORS_ALLOWED_ORIGINS 에 화면 주소가 있어야 한다
 * (docs/aws-elb-asg-guide.md). 빌드할 때 값이 굳으므로 바꾸면 화면을 다시 배포한다.
 */
function readApiOrigin(raw: string | undefined): string {
  const value = (raw ?? "").trim();
  if (!value) return "";
  try {
    // 출처만 쓴다 — 끝에 /api 나 / 를 붙여 넣어도 같은 결과가 되게 한다.
    return new URL(value).origin;
  } catch {
    // 잘못된 값으로 조용히 같은 출처를 부르면 원인을 찾기 어렵다. 콘솔에 남기고 같은 출처로 둔다.
    console.error(`VITE_API_BASE_URL 이 올바른 주소가 아닙니다: "${value}" — https://api.example.com 처럼 넣으세요.`);
    return "";
  }
}

/** API 서버의 출처. 비어 있으면 화면과 같은 출처다. */
export const API_BASE_URL: string = readApiOrigin(import.meta.env.VITE_API_BASE_URL);

/** `/api/...` 경로면 API 서버 주소를 붙이고, 그 밖의 값(정적 파일·전체 주소·blob:)은 그대로 돌려준다. */
export function apiUrl(path: string): string {
  return API_BASE_URL && path.startsWith("/api/") ? API_BASE_URL + path : path;
}
