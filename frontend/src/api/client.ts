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

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const token = getToken();

  let response: Response;
  try {
    response = await fetch(path, {
      ...options,
      headers: {
        // FormData일 때 Content-Type을 우리가 정하면 안 된다. 브라우저가
        // multipart 경계문자열(boundary)까지 넣어 만들어야 서버가 파싱할 수 있다.
        ...(options.body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
    });
  } catch (cause) {
    // fetch는 서버에 닿지 못하면 TypeError를 던진다. 그대로 두면 호출부의
    // `err instanceof ApiError` 검사를 통과하지 못해 "알 수 없는 오류"로 뭉개진다.
    // 여기서 ApiError로 감싸 두면 서버가 준 오류와 같은 방식으로 다룰 수 있다.
    throw new ApiError({
      status: NETWORK_ERROR_STATUS,
      message: "서버에 연결할 수 없습니다.",
      details: cause,
    });
  }

  const body = await readBody(response);

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
