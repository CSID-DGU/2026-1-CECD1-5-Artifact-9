package com.artifact.diagnosis.gemini;

/**
 * {@link GeminiClient} 호출 결과. 예외 대신 이 객체로 성공/실패를 구분한다 — Gemini 호출은
 * 절대 예외를 던지지 않는다는 게 이 프로젝트의 설계 전제라, 실패도 값으로 돌려준다.
 */
public record GeminiResult(boolean success, String text, String model, int errorCode, String errorMessage) {

    public static GeminiResult success(String text, String model) {
        return new GeminiResult(true, text, model, 0, null);
    }

    public static GeminiResult failure(String message) {
        return new GeminiResult(false, null, null, 0, message);
    }

    public static GeminiResult apiError(int code, String message) {
        return new GeminiResult(false, null, null, code, message);
    }

    /** 503(과부하)인지 여부 — 호출부가 사용자에게 재시도 안내 문구를 보여줄 때 쓴다. */
    public boolean isOverloaded() {
        return errorCode == 503;
    }
}
