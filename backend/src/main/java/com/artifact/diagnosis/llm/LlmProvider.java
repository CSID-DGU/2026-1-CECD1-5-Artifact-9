package com.artifact.diagnosis.llm;

import java.time.Duration;

/**
 * LLM 공급자 하나(정확히는 공급자 + 모델 한 개). {@link LlmGateway} 가 순서대로 시도한다.
 *
 * 구현체는 예외를 던지지 않는다. 실패는 {@link LlmResult#failure} 로 돌려준다.
 */
public interface LlmProvider {

    /** 로그와 쿨다운 표에 쓰는 이름. 예: "gemini:gemini-3.1-flash-lite". */
    String name();

    /** 키·모델명이 있어 호출할 수 있는 상태인지. 아니면 체인에서 빠진다. */
    boolean isConfigured();

    /**
     * @param maxWait 이 호출에 쓸 수 있는 최대 시간. 공급자 자체 타임아웃보다 짧으면 이 값을 쓴다 —
     *                체인 전체가 요청 스레드를 붙잡는 시간을 묶어 두기 위해서다.
     */
    LlmResult generate(LlmRequest request, Duration maxWait);
}
