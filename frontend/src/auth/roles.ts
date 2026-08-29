/**
 * 직책별 화면 접근 규칙. 백엔드의 @StaffAccess / @MedicalAccess / @DoctorAccess 와 짝이다.
 *
 * 여기는 안내이지 방어가 아니다. 브라우저 코드는 사용자가 고칠 수 있으므로,
 * 실제 차단은 언제나 서버(@PreAuthorize)가 한다. 이 파일의 목적은 "눌러봐야 403이 뜨는 버튼"을
 * 애초에 보여주지 않아, 권한 없는 사람이 헛수고하지 않게 하는 것이다.
 *
 * 규칙을 바꿀 때는 반드시 백엔드 애노테이션을 함께 바꿔야 한다.
 * 프론트만 열어두면 사용자는 화면을 볼 수 있는데 API가 전부 403이라 더 나쁜 경험이 된다.
 */

/** 누적 사다리 — 아래로 갈수록 권한이 넓다. STAFF ⊂ NURSE ⊂ DOCTOR ⊂ ADMIN */
export const ROLE_RANK = {
  STAFF: 1,
  NURSE: 2,
  DOCTOR: 3,
  ADMIN: 4,
} as const;

export type Role = keyof typeof ROLE_RANK;

/** 서버가 준 role 문자열을 사다리 값으로. 모르는 값은 0 — 아무 화면도 열리지 않는다. */
export function rankOf(role: string | undefined | null): number {
  if (!role) return 0;
  return ROLE_RANK[role.toUpperCase() as Role] ?? 0;
}

export function hasAtLeast(role: string | undefined | null, required: Role): boolean {
  return rankOf(role) >= ROLE_RANK[required];
}

/** 화면별 최소 직책. MainLayout 의 탭 노출과 RoleRoute 가 이 표 하나를 공유한다. */
export const SCREEN_MIN_ROLE = {
  /** 접수 — 환자 등록·검색, 접수 생성, QR 발급 */
  reception: "STAFF",
  /** 진료 — 이미지 업로드·AI 분석(간호사 이상), 처방 저장은 그 안에서 의사만 */
  clinic: "NURSE",
  /** 조회 — 지난 진료·처방·분석 결과 열람 */
  lookup: "STAFF",
  /** 증명 — 서류 출력 */
  certificate: "STAFF",
} as const satisfies Record<string, Role>;

export type Screen = keyof typeof SCREEN_MIN_ROLE;

export function canOpen(role: string | undefined | null, screen: Screen): boolean {
  return hasAtLeast(role, SCREEN_MIN_ROLE[screen]);
}

/** 권한이 있는 화면 중 첫 번째 — 로그인 직후와 권한 없는 경로 접근 시 보낼 곳. */
export function landingPathFor(role: string | undefined | null): string {
  if (canOpen(role, "reception")) return "/main";
  if (canOpen(role, "clinic")) return "/main/clinic";
  return "/";
}
