package com.artifact.diagnosis.print;

/**
 * 백엔드와 print-agent 가 인쇄 큐를 두고 주고받는 메시지.
 *
 * 이 두 record 는 맥북에서 도는 파이썬 쪽 코드와 짝을 이룬다
 * ({@code print-agent/poller.py}). 필드 이름을 바꾸면 반드시 그쪽도 같이 고쳐야 한다.
 */
public final class PrintJobMessages {

    private PrintJobMessages() {
    }

    /**
     * 백엔드 → 에이전트. "이걸 뽑아라".
     *
     * @param jobId   결과를 되돌려줄 때 쓰는 식별자
     * @param docType {@code ticket} / {@code visit-summary} / {@code certificate-slip}.
     *                에이전트의 {@code documents.BUILDERS} 키와 같은 값이다.
     * @param payload 문서 본문. 지금까지 HTTP 본문으로 보내던 것과 똑같은 모양이라
     *                에이전트의 문서 조립 코드는 손대지 않아도 된다.
     */
    public record Envelope(String jobId, String docType, Object payload) {
    }

    /**
     * 에이전트 → 백엔드. "뽑았다 / 못 뽑았다".
     *
     * 실패해도 400 을 만들지 않는다. 용지 없음·프린터 꺼짐은 정상적으로 일어나는 일이고,
     * 그 사실을 백엔드에 알리는 것 자체는 성공한 요청이기 때문이다.
     *
     * @param ok     출력 성공 여부
     * @param detail 실패 사유. 화면의 '인쇄' 버튼을 누른 사람에게 그대로 보여준다.
     */
    public record Report(boolean ok, String detail) {
    }
}
