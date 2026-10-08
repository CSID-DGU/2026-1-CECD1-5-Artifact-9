import { apiRequest } from "./client";

/**
 * 이 응답만 인증 없이 나가므로 토큰 외에는 아무것도 담기지 않는다.
 * 환자 이름·접수번호는 /kiosk/{token} 으로 이동한 뒤 getKioskSession 에서 받는다.
 */
export type KioskPending = {
  kioskToken: string;
};

export type KioskSession = {
  visitId: number;
  patientName: string;
  receptionNumber: string;
  /** 이미 예비분석이 끝난 접수인지. true여도 재촬영/재분석은 가능하다. */
  analyzed: boolean;
};

export type PreliminaryTopK = {
  diseaseCode: string;
  diseaseNameKo: string;
  confidence: number;
};

export type PreliminaryAnalysis = {
  topK: PreliminaryTopK[];
  /** AI 신뢰도 등급. "low"면 caution 문구를 띄운다 — 자세한 배경은 api/analysis.ts 주석 참고. */
  confidenceLevel: "low" | "normal";
  /** 확신도가 낮을 때 띄울 문구. 경고할 것이 없으면 null. */
  caution: string | null;
  /**
   * 서버가 호출자에 맞는 경로를 내려준다 — 태블릿은 /api/kiosk/session/{token}/heatmap(무인증),
   * 의사 화면은 /api/v1/visits/{visitId}/preliminary/heatmap(JWT 필요). 직접 조립하지 말 것.
   */
  gradcamUrl: string | null;
  /**
   * AI 참고 소견. 분석 직후에는 **항상 null** 이다 — 소견은 결과를 먼저 보여준 뒤
   * {@link generateKioskComment} 로 따로 채운다. 의사 화면 조회(getPreliminaryAnalysis)에서는
   * 이미 저장된 값이 들어온다.
   */
  aiComment: string | null;
  analyzedAt: string;
};

export type KioskComment = {
  /** 생성에 실패해도 null 이 아니라 안내 문구가 담긴다. */
  aiComment: string;
};

/**
 * 분석 응답 대기 한도.
 *
 * 앞단 nginx 가 60초에 끊는다(frontend/nginx.conf 의 proxy_read_timeout). 그보다 **먼저**
 * 우리가 끊어야 사용자가 nginx 의 504 HTML 대신 우리 문구를 본다.
 * 서버 쪽 상한은 FastAPI 호출 30초(application.properties 의 fastapi.timeout-seconds)이므로
 * 정상 경로는 여기에 닿지 않는다 — 45초는 업로드 시간까지 감안한 여유다.
 */
const ANALYZE_TIMEOUT_MS = 45_000;

/**
 * 소견 대기 한도. 분석보다 짧게 잡는다 — 소견은 없어도 결과를 읽는 데 지장이 없는 정보라
 * 오래 붙잡을 이유가 없다. Gemini 는 재시도까지 하면 최악 48초라 여기에 걸릴 수 있는데,
 * 그때는 화면에서 소견 자리만 조용히 사라진다(분석은 이미 성공한 상태다).
 */
const COMMENT_TIMEOUT_MS = 20_000;

/**
 * QR 없이 자동 진입하는 폴백(/kiosk?auto=1)용.
 * 서버에서 기본 비활성이라(KIOSK_AUTO_PENDING) 대기 환자가 없을 때와 똑같이 404가 온다 — 호출부에서 무시한다.
 */
export function getKioskPending() {
  return apiRequest<KioskPending>(`/api/kiosk/pending`);
}

/** QR 토큰으로 접수 정보 조회. 토큰이 유효하지 않으면 404. */
export function getKioskSession(token: string) {
  return apiRequest<KioskSession>(`/api/kiosk/session/${encodeURIComponent(token)}`);
}

export function analyzeKioskSession(token: string, file: File) {
  const formData = new FormData();
  formData.append("file", file);

  return apiRequest<PreliminaryAnalysis>(`/api/kiosk/session/${encodeURIComponent(token)}/analyze`, {
    method: "POST",
    body: formData,
    timeoutMs: ANALYZE_TIMEOUT_MS,
  });
}

/**
 * 직전 분석 결과에 대한 AI 참고 소견을 만든다. {@link analyzeKioskSession} 성공 직후에 부른다.
 *
 * POST 인 이유는 DB 쓰기와 유료 외부 API 호출이라는 부수효과가 있기 때문이다.
 * 서버가 같은 분석에 대해 두 번 만들지 않으므로(이미 있으면 그대로 반환) 재호출은 안전하다.
 */
export function generateKioskComment(token: string) {
  return apiRequest<KioskComment>(`/api/kiosk/session/${encodeURIComponent(token)}/comment`, {
    method: "POST",
    timeoutMs: COMMENT_TIMEOUT_MS,
  });
}

/** 의사 진료 페이지 조회용. 예비분석이 없으면 404 — 호출부에서 catch(() => null)로 처리한다. */
export function getPreliminaryAnalysis(visitId: number) {
  return apiRequest<PreliminaryAnalysis>(`/api/v1/visits/${visitId}/preliminary`);
}
