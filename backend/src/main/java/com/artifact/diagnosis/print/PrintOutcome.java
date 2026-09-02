package com.artifact.diagnosis.print;

/**
 * 감열지 출력 결과.
 *
 * 자동 출력에서는 이 값을 로그로만 쓰고 버린다 — 프린터가 꺼져 있다고 접수나
 * 발급이 막히면 안 되기 때문이다. 화면의 '인쇄' 버튼처럼 사람이 직접 누른
 * 경우에는 그대로 응답에 실어 결과를 알려준다.
 */
public record PrintOutcome(boolean ok, String detail) {

    public static PrintOutcome success() {
        return new PrintOutcome(true, "출력했습니다.");
    }

    public static PrintOutcome failure(String detail) {
        return new PrintOutcome(false, detail);
    }
}
