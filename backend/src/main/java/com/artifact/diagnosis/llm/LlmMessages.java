package com.artifact.diagnosis.llm;

/**
 * 실패한 {@link LlmResult} 를 화면에 보일 첫 문장으로 바꾼다.
 *
 * 공급자 원문 메시지(영문, 상태코드 포함)는 로그에만 남기고 화면에는 이 문장을 쓴다.
 * 뒤에 붙일 다음 행동 안내("직접 작성해 주세요", "의사의 확인 진료가 필요합니다")는 기능마다
 * 달라서 호출부가 붙인다.
 */
public final class LlmMessages {

    private LlmMessages() {
    }

    public static String reason(LlmResult result) {
        if (result.failure() == null) return "AI 응답을 받지 못했습니다.";
        return switch (result.failure()) {
            case NOT_CONFIGURED -> "AI API 키가 설정되지 않았습니다.";
            case OVERLOADED, RATE_LIMITED, SERVER_ERROR, TIMEOUT, NETWORK -> "AI 서버가 일시적으로 혼잡합니다.";
            case BLOCKED -> "AI가 안전 정책상 응답을 거부했습니다.";
            case EMPTY -> "AI가 빈 응답을 반환했습니다.";
            case AUTH, MODEL_NOT_FOUND, BAD_REQUEST -> "AI 설정 오류로 응답을 받지 못했습니다. 관리자에게 알려 주세요.";
            case BAD_RESPONSE -> "AI 응답을 해석하지 못했습니다.";
            case INTERRUPTED -> "AI 호출이 중단되었습니다.";
        };
    }
}
