import type { Member } from "../auth/AuthContext";

/**
 * 로그인 없이 체험하는 데모 모드.
 *
 * 화면은 운영과 똑같은 것을 쓰고, /api 요청만 이 탭 안의 가짜 백엔드(demo/backend.ts)가 받는다
 * (demo/installDemoFetch.ts). 운영 서버·DB 에는 아무것도 가지 않는다 — 로그인 없이 들어온 사람이 실제 환자
 * 기록을 보거나 남기지 않게 하려는 것이다. 사람마다 자기 브라우저 탭 안에서 따로 돈다.
 *
 * 데모 표시와 데이터는 sessionStorage 에 둔다(이 탭에만 있다).
 *   - 탭을 닫으면 데모가 끝난다. 공용 기기(부스 노트북·키오스크 폰)에 데모 상태가 남지 않는다.
 *   - 같은 브라우저 다른 탭의 실제 로그인(localStorage 의 his_token)은 건드리지 않는다.
 */
const FLAG_KEY = "artifact_demo_mode";
export const DEMO_DB_KEY = "artifact_demo_db";
export const DEMO_TOKEN = "demo-session";
export const DEMO_USER: Member = { memberId: 900, loginId: "demo", name: "체험 의사", role: "DOCTOR" };
/** 가짜 백엔드가 데이터를 바꿀 때마다 window 에 쏘는 이벤트 — 데모 안내 패널이 진행 상황을 다시 읽는다. */
export const DEMO_CHANGE_EVENT = "artifact-demo-change";

export function isDemoActive(): boolean {
  try {
    return sessionStorage.getItem(FLAG_KEY) === "1";
  } catch {
    return false;
  }
}

/** 데모를 처음부터 시작한다 — 가상 데이터는 다음 요청 때 새로 만들어진다. */
export function beginDemo(): void {
  sessionStorage.setItem(FLAG_KEY, "1");
  sessionStorage.removeItem(DEMO_DB_KEY);
}

/** 데모를 끝내고 가상 데이터를 지운다. */
export function endDemo(): void {
  try {
    sessionStorage.removeItem(FLAG_KEY);
    sessionStorage.removeItem(DEMO_DB_KEY);
  } catch {
    /* 저장소를 못 쓰면 지울 것도 없다 */
  }
}

/** 데모는 그대로 두고 가상 데이터만 처음 상태로 되돌린다. */
export function resetDemoData(): void {
  try {
    sessionStorage.removeItem(DEMO_DB_KEY);
  } catch {
    /* 무시 */
  }
}
