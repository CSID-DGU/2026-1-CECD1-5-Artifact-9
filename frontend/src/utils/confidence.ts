/**
 * 화면에 표시할 신뢰도 상한(%).
 *
 * 모델이 내놓는 값은 softmax 결과라 정확히 1.0이 되는 일은 거의 없다. 화면의 "100.0%"는
 * 대개 99.97 같은 값이 소수 첫째 자리에서 반올림된 것이다. 그런데 이 화면은 환자와 의료진이
 * 함께 보는 자리라, 반올림 흔적이 "AI가 확신한다"로 읽힌다. 확률 모델은 어떤 경우에도
 * 100%를 주장할 수 없고, 오진 시 그 표시는 그대로 근거로 남는다.
 *
 * 그래서 표시 단계에서만 상한을 둔다. 저장·전송되는 원본 값은 건드리지 않는다.
 */
const MAX_DISPLAY_PERCENT = 99.9;

/**
 * 0~1 사이의 신뢰도를 화면용 문자열로 바꾼다. (예: 0.9997 → "99.9%")
 *
 * @param confidence 서버가 내려준 신뢰도. 응답 경로에 따라 문자열로 오는 곳이 있어 둘 다 받는다.
 */
export function formatConfidence(confidence: number | string): string {
  const percent = Number(confidence) * 100;
  if (!Number.isFinite(percent)) return "-";

  return `${Math.min(percent, MAX_DISPLAY_PERCENT).toFixed(1)}%`;
}
