package com.artifact.diagnosis.analysis;

/**
 * 올린 파일을 AI 서버가 사진으로 열지 못했다 — 이미지가 아닌 파일, AI 서버가 읽지 못하는 형식(HEIC 등),
 * 손상되었거나 잘린 파일, 0바이트. 모델이나 서버가 아니라 입력의 문제라 다른 사진을 올리면 풀린다.
 *
 * {@link IllegalArgumentException} 을 상속해 {@code GlobalExceptionHandler#handleBadRequest} 가 400 으로 내보낸다.
 * 프론트(api/errors.ts)는 4xx 의 서버 메시지를 그대로 띄우므로 이 메시지가 곧 화면 문구다. 태블릿 앞의 환자도 읽는
 * 문장이라 FastAPI 가 적어 준 사유(어느 형식이 몇 바이트에서 잘렸는지)는 싣지 않고 로그에만 남긴다({@link FastApiErrors}).
 *
 * {@code IllegalArgumentException} 을 그대로 던지지 않고 이 타입을 따로 두는 이유: FastAPI 호출부(callFastApi)는 이 예외만
 * 골라 그대로 올리고 나머지는 500 으로 감싼다. 같은 try 안의 {@code URI.create}(잘못된 fastapi.url)나
 * {@code HttpRequest.Builder.header}(잘못된 시크릿 값)도 {@code IllegalArgumentException} 을 던지는데, 그것들은
 * 설정 오류라 400("다시 촬영")으로 새어 나가면 안 된다.
 */
public class InvalidImageException extends IllegalArgumentException {

    /** 태블릿(키오스크 예비분석)과 진료실(분석) 화면에 그대로 뜨는 문구. 어느 화면이든 다음 행동이 같아 하나로 쓴다. */
    public static final String MESSAGE = "사진을 읽을 수 없습니다. JPEG/PNG 사진으로 다시 촬영해 주세요.";

    public InvalidImageException() {
        super(MESSAGE);
    }
}
