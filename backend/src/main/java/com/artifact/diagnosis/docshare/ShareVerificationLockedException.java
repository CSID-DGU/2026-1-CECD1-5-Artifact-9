package com.artifact.diagnosis.docshare;

/**
 * 본인 확인 실패가 누적되어 잠시 잠긴 상태.
 *
 * 생년월일은 경우의 수가 3만 남짓이라, 막지 않으면 링크를 손에 넣은 사람이 전수 대입으로
 * 뚫을 수 있다. 실제 방어는 {@link ShareAccessGuard} 가 하고, 이 예외는 그 결과를
 * 429 Too Many Requests 로 옮기는 통로다.
 */
public class ShareVerificationLockedException extends RuntimeException {
    public ShareVerificationLockedException(String message) {
        super(message);
    }
}
