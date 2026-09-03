package com.artifact.diagnosis.docshare;

/**
 * 열람 링크의 본인 확인(생년월일)이 틀렸을 때.
 *
 * 404(없는 링크)·410(기간 만료)과 분리해 둔 이유는 환자가 해야 할 행동이 다르기 때문이다 —
 * 이쪽은 다시 입력하면 되고, 앞의 둘은 병원에 문의해야 한다.
 *
 * 401 이 아니라 403 으로 나가는 이유: 프론트의 {@code apiRequest} 는 401 을 "세션 만료"로 보고
 * 로그아웃 절차를 태운다. 이 화면에는 애초에 로그인 세션이 없으므로 그 경로에 얹으면 안 된다.
 */
public class ShareVerificationFailedException extends RuntimeException {
    public ShareVerificationFailedException(String message) {
        super(message);
    }
}
