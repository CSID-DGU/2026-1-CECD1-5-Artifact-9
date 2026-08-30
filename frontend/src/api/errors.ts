import { ApiError, NETWORK_ERROR_STATUS } from "./client";

/**
 * 사용자에게 보여줄 오류 문구를 만든다.
 *
 * 전에는 화면마다 `error instanceof Error ? error.message : "요청 처리 중 오류가 발생했습니다."`를
 * 각자 복사해 두고 있었다(Clinic.tsx, Reception.tsx). 두 가지가 문제였다:
 *
 *   - 서버가 본문 없이 상태 코드만 돌려주면 `error.message`가 빈 문자열이라
 *       화면에 아무것도 안 뜬다. 사용자는 버튼을 눌렀는데 반응이 없는 것처럼 본다.
 *   - 500 응답의 메시지가 `NullPointerException: null` 같은 자바 내부 정보였다.
 *       사용자에게 의미도 없고 내부 구조만 노출된다.
 *
 * 그래서 규칙을 하나로 정한다 —
 * 서버가 사람이 읽을 메시지를 줬으면 그걸 쓰고, 아니면 상태 코드에 맞는 기본 문구를 쓴다.
 * 단 5xx는 서버 메시지를 쓰지 않고 항상 기본 문구로 덮는다. 서버 쪽에서도 예외 문자열을 응답에
 * 담지 않도록 고쳤지만(GlobalExceptionHandler.handleEtc), 프론트에서 한 번 더 막아
 * 다른 경로로 5xx가 나가도 내부 문자열이 화면에 뜨지 않게 한다.
 */

/** 서버 메시지가 없을 때 쓸 문구. 사용자가 다음에 뭘 하면 되는지까지 적는다. */
const FALLBACK_BY_STATUS: Record<number, string> = {
  [NETWORK_ERROR_STATUS]: "서버에 연결할 수 없습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.",
  400: "입력값을 확인해 주세요.",
  401: "로그인이 만료되었습니다. 다시 로그인해 주세요.",
  403: "이 작업을 수행할 권한이 없습니다. 담당자에게 문의해 주세요.",
  404: "요청한 정보를 찾을 수 없습니다.",
  408: "요청 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.",
  409: "지금 상태에서는 처리할 수 없는 요청입니다. 화면을 새로고침한 뒤 다시 시도해 주세요.",
  413: "파일 용량이 너무 큽니다. 더 작은 이미지로 다시 시도해 주세요.",
  415: "지원하지 않는 파일 형식입니다.",
  // 422 는 일부러 비워 둔다. 예전에는 "확신도가 낮은 이미지"를 서버가 422 로 막아서 이 자리에
  // 재촬영 안내가 있었지만, 이제는 결과를 그대로 주고 경고만 붙인다(LowConfidenceBanner 참고).
  // 남겨 두면 엉뚱한 422 에 "다시 촬영해 주세요"가 붙으므로 GENERIC 으로 떨어뜨린다.
  429: "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.",
  500: "서버 내부 오류가 발생했습니다. 문제가 계속되면 담당자에게 알려 주세요.",
  502: "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
  503: "AI 분석 서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.",
  504: "서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.",
};

const GENERIC = "요청 처리 중 오류가 발생했습니다.";

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const fallback = FALLBACK_BY_STATUS[error.status] ?? GENERIC;

    // 5xx 메시지는 내부 예외 문자열이라 그대로 노출하지 않는다.
    if (error.status >= 500) return fallback;

    const serverMessage = error.message?.trim();
    return serverMessage ? serverMessage : fallback;
  }

  if (error instanceof Error && error.message.trim()) {
    return error.message.trim();
  }

  return GENERIC;
}

/** 재로그인이 필요한 오류인가 (권한 부족 403과 구분된다). */
export function isSessionExpired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

/** 로그인은 유효하지만 직책 권한이 모자란 오류인가. */
export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/** 서버에 자원이 없음 — 호출부가 "없으면 null" 로 넘기는 데 쓴다. */
export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}
