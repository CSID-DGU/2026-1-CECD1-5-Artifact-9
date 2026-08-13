package com.artifact.diagnosis.analysis;

/**
 * 외부 AI 서버(FastAPI)에 연결하지 못했거나 제한 시간 안에 응답을 받지 못했을 때.
 *
 * <p>서버 내부 버그가 아니라 <b>일시적인 외부 장애</b>이므로 500이 아니라 503으로 응답한다
 * ({@code GlobalExceptionHandler}). 화면은 "잠시 후 다시 시도"를 안내할 수 있고,
 * 500과 구분되므로 운영 중 로그만 봐도 원인이 우리 코드인지 AI 서버인지 바로 갈린다.
 */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(String message) {
        super(message);
    }
}
