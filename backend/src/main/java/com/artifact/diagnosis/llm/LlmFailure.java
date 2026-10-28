package com.artifact.diagnosis.llm;

/**
 * 생성이 실패한 이유. {@link LlmGateway} 는 이 값으로 다음 공급자로 넘어갈지,
 * 그 공급자를 얼마 동안 쉬게 할지를 정한다.
 */
public enum LlmFailure {

    /** 키나 모델명이 없어 호출하지 않았다. */
    NOT_CONFIGURED,
    /** 공급자 쪽 용량 부족. Gemini 503 UNAVAILABLE, Claude 529 overloaded_error. */
    OVERLOADED,
    /** 요청 한도 초과. 429. */
    RATE_LIMITED,
    /** 그 밖의 5xx. */
    SERVER_ERROR,
    /** 응답을 기다리다 시간이 다 됐다. */
    TIMEOUT,
    /** 연결 자체가 안 됐다(DNS, TLS, 끊김). */
    NETWORK,
    /** 안전 정책으로 거절됐다(Gemini SAFETY·blockReason, Claude stop_reason=refusal). */
    BLOCKED,
    /** 성공 응답인데 쓸 수 있는 답이 없다(빈 응답, 길이 제한으로 잘림). */
    EMPTY,
    /** 요청 형식 오류. 400. 우리 코드의 문제라 다른 공급자로 가도 같을 수 있지만, 문법이 달라 시도는 한다. */
    BAD_REQUEST,
    /** 키가 틀렸거나 권한이 없다. 401·403. */
    AUTH,
    /** 모델 ID 가 없거나 이 계정에서 쓸 수 없다. 404. */
    MODEL_NOT_FOUND,
    /** 응답을 해석하지 못했다(요청한 JSON 형식이 아님, 응답 처리 중 오류). */
    BAD_RESPONSE,
    /** 요청 스레드가 중단됐다. */
    INTERRUPTED;

    /** 잠시 뒤에는 될 수 있는 실패. 화면에는 "일시적으로 혼잡"으로 안내한다. */
    public boolean isTemporary() {
        return switch (this) {
            case OVERLOADED, RATE_LIMITED, SERVER_ERROR, TIMEOUT, NETWORK -> true;
            default -> false;
        };
    }

    /** 설정을 고치기 전에는 계속 실패하는 경우. 같은 공급자를 오래 쉬게 한다. */
    public boolean isConfigurationProblem() {
        return switch (this) {
            case AUTH, MODEL_NOT_FOUND -> true;
            default -> false;
        };
    }
}
