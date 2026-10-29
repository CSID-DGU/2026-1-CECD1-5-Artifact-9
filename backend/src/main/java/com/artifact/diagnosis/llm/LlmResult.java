package com.artifact.diagnosis.llm;

import java.util.List;

/**
 * 생성 결과. 예외 대신 값으로 성공·실패를 돌려준다 — AI 호출이 죽어도 진료가 멈추면 안 된다.
 *
 * @param provider   "gemini" 또는 "claude". 실패 결과에서도 마지막으로 시도한 공급자를 담는다.
 * @param model      실제로 응답한(또는 마지막으로 실패한) 모델 ID. 화면과 DB 에 그대로 남는다.
 * @param statusCode 공급자가 돌려준 HTTP 상태. 응답을 받기 전에 끊겼으면 0.
 * @param message    실패 사유 원문(공급자 메시지). 사용자 안내 문구가 아니라 로그·진단용이다.
 * @param attempts   이번 요청에서 거친 공급자 순서와 결과(예: "gemini:gemini-3.1-flash-lite=OVERLOADED(503)").
 */
public record LlmResult(
        boolean success,
        String text,
        String provider,
        String model,
        LlmFailure failure,
        int statusCode,
        String message,
        List<String> attempts
) {

    public static LlmResult success(String text, String provider, String model) {
        return new LlmResult(true, text, provider, model, null, 200, null, List.of());
    }

    public static LlmResult failure(String provider, String model, LlmFailure failure, int statusCode, String message) {
        return new LlmResult(false, null, provider, model, failure, statusCode, message, List.of());
    }

    public LlmResult withAttempts(List<String> trail) {
        return new LlmResult(success, text, provider, model, failure, statusCode, message, List.copyOf(trail));
    }

    /** 사용자에게 "잠시 후 다시"를 안내해도 되는 실패인지. */
    public boolean isTemporaryFailure() {
        return !success && failure != null && failure.isTemporary();
    }

    /** 로그 한 줄용 요약. */
    public String summary() {
        if (success) return provider + ":" + model + "=OK";
        return provider + ":" + model + "=" + failure + (statusCode > 0 ? "(" + statusCode + ")" : "");
    }
}
