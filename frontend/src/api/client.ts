import { getToken, notifySessionExpired } from "./session";

export type ApiErrorBody = {
  timestamp?: string;
  status: number;
  message: string;
  details?: unknown;
};

/**
 * 서버가 돌려준 오류, 또는 서버에 닿지도 못한 실패.
 *
 * `status`는 HTTP 상태 코드이고, 네트워크 실패처럼 응답 자체가 없는 경우에만
 * {@link NETWORK_ERROR_STATUS}(0)이다. 화면에 뿌릴 문구는 직접 만들지 말고
 * `getErrorMessage()`(./errors)에 맡긴다.
 */
export class ApiError extends Error {
  status: number;
  details?: unknown;

  constructor(body: ApiErrorBody) {
    super(body.message);
    this.name = "ApiError";
    this.status = body.status;
    this.details = body.details;
  }
}

/** 응답을 아예 받지 못했을 때 쓰는 가짜 상태 코드 (서버 꺼짐, 오프라인, DNS 실패 등). */
export const NETWORK_ERROR_STATUS = 0;

/**
 * 서버가 아니라 **우리가** 대기 한도를 넘겨 직접 끊었을 때 붙이는 상태 코드.
 *
 * 실제 HTTP 코드(408 Request Timeout)를 빌려 쓴다. 이 서버는 408을 내보내지 않으므로
 * 겹칠 일이 없고, `getErrorMessage()` 의 기존 문구표를 그대로 태울 수 있다.
 * "서버에 못 닿았다"(0)와는 갈라 두어야 한다 — 부스에서 이 둘은 대응이 완전히 다르다.
 * 0은 와이파이를 보는 것이고, 408은 서버가 붐비니 잠시 기다리는 것이다.
 */
export const REQUEST_TIMEOUT_STATUS = 408;

export type ApiRequestOptions = RequestInit & {
  /**
   * 응답 대기 한도(ms). 주지 않으면 걸지 않는다 — 브라우저 기본은 사실상 무한이다.
   *
   * `AbortSignal.timeout()` 을 쓰지 않는 이유: Safari 16 이상에서만 있다. 부스에 나갈
   * 아이패드의 iOS 버전을 우리가 정할 수 없으므로 AbortController 로 직접 만든다.
   */
  timeoutMs?: number;
};

export async function apiRequest<T>(
  path: string,
  options: ApiRequestOptions = {}
): Promise<T> {
  const { timeoutMs, ...init } = options;
  const token = getToken();

  // 한도를 준 요청만 컨트롤러를 만든다. 나머지는 이전과 완전히 같은 경로를 탄다.
  const controller = timeoutMs ? new AbortController() : null;
  let timedOut = false;
  const timer =
    controller && timeoutMs
      ? setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs)
      : null;

  // 호출부가 자기 signal 을 함께 준 경우에도 취소가 전달되게 이어붙인다.
  // (AbortSignal.any() 는 Safari 17.4+ 라 여기서도 쓸 수 없다)
  if (controller && init.signal) {
    if (init.signal.aborted) controller.abort();
    else init.signal.addEventListener("abort", () => controller.abort(), { once: true });
  }

  let response: Response;
  let body: unknown;
  try {
    response = await fetch(path, {
      ...init,
      signal: controller ? controller.signal : init.signal,
      headers: {
        // FormData일 때 Content-Type을 우리가 정하면 안 된다. 브라우저가
        // multipart 경계문자열(boundary)까지 넣어 만들어야 서버가 파싱할 수 있다.
        ...(init.body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...init.headers,
      },
    });
    // 본문 읽기까지 이 안에 둔다. 한도를 넘겨 abort 하면 응답 헤더는 이미 왔더라도
    // 본문 스트림이 끊기면서 여기서 터지는데, 밖에 두면 그 오류만 ApiError 로 감싸이지 않는다.
    body = await readBody(response);
  } catch (cause) {
    // 우리가 건 한도에 걸린 경우. 아래 네트워크 실패와 구분해서 올려보낸다.
    if (timedOut) {
      throw new ApiError({
        status: REQUEST_TIMEOUT_STATUS,
        // 문구는 만들지 않는다 — getErrorMessage() 의 408 항목이 채운다.
        message: "",
        details: cause,
      });
    }
    // 호출부가 스스로 취소한 경우(화면 이동 등)는 오류가 아니다. 원본을 그대로 올려
    // 호출부가 `err.name === "AbortError"` 로 조용히 흘려보낼 수 있게 한다.
    if (init.signal?.aborted) throw cause;

    // fetch는 서버에 닿지 못하면 TypeError를 던진다. 그대로 두면 호출부의
    // `err instanceof ApiError` 검사를 통과하지 못해 "알 수 없는 오류"로 뭉개진다.
    // 여기서 ApiError로 감싸 두면 서버가 준 오류와 같은 방식으로 다룰 수 있다.
    throw new ApiError({
      status: NETWORK_ERROR_STATUS,
      message: "서버에 연결할 수 없습니다.",
      details: cause,
    });
  } finally {
    if (timer !== null) clearTimeout(timer);
  }

  if (!response.ok) {
    // 401 = 토큰이 없거나 만료·위조됨. 다시 로그인시켜야 풀린다.
    // 403(권한 부족)은 다시 로그인해도 그대로이므로 로그아웃시키지 않는다.
    //
    // 토큰이 없던 요청까지 여기서 처리하면, 로그인 전에 우연히 뜬 요청 하나가
    // "세션이 만료됐습니다"를 띄우게 된다. 실제로 가지고 있던 세션이 끊긴 경우만 알린다.
    if (response.status === 401 && token) {
      notifySessionExpired();
    }

    if (isErrorBody(body)) {
      throw new ApiError(body);
    }
    throw new ApiError({
      status: response.status,
      // 본문이 비어 있으면(예전 Spring Security 기본 403이 그랬다) 여기서 문자열을
      // 만들지 않고 비워 둔다. 상태 코드에 맞는 문구는 getErrorMessage()가 채운다.
      message: typeof body === "string" ? body.trim() : "",
    });
  }

  return body as T;
}

/**
 * 응답 본문을 안전하게 읽는다.
 *
 * 본문이 없는 응답(204 No Content, Content-Length: 0)에 `response.json()`을 부르면
 * "Unexpected end of JSON input"으로 터진다. 정작 요청은 성공했는데 실패로 보이게 된다.
 */
async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return null;
  }

  const text = await response.text();
  if (!text) return null;

  if ((response.headers.get("content-type") ?? "").includes("application/json")) {
    try {
      return JSON.parse(text);
    } catch {
      // JSON이라고 해놓고 JSON이 아니면(리버스 프록시가 낀 오류 페이지 등) 원문을 그대로 넘긴다.
      return text;
    }
  }
  return text;
}

function isErrorBody(body: unknown): body is ApiErrorBody {
  return (
    typeof body === "object" &&
    body !== null &&
    "status" in body &&
    typeof (body as ApiErrorBody).message === "string"
  );
}
