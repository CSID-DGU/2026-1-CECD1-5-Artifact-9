export type ApiErrorBody = {
  timestamp?: string;
  status: number;
  message: string;
  details?: unknown;
};

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

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(options.body instanceof FormData
        ? {}
        : { "Content-Type": "application/json" }),
      ...options.headers,
    },
  });

  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json")
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    if (typeof body === "object" && body !== null && "status" in body) {
      throw new ApiError(body as ApiErrorBody);
    }
    throw new ApiError({
      status: response.status,
      message: typeof body === "string" ? body : "API 요청에 실패했습니다.",
    });
  }

  return body as T;
}
