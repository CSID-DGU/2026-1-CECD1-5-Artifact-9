package com.artifact.diagnosis.print;

/**
 * 감열지 출력물을 프린터가 물린 맥북까지 실어 나르는 통로.
 *
 * <h2>왜 통로를 두 가지로 나눴나</h2>
 * 백엔드는 EC2 컨테이너 안에서 돌고 프린터는 접수 데스크 맥북 USB 에 물려 있다.
 * 둘을 잇는 방법이 두 가지인데, 방향이 정반대다.
 *
 * <pre>
 *   direct : [EC2 백엔드] --HTTP--> [맥북 print-agent] --USB--> [프린터]
 *   queue  : [EC2 백엔드] &lt;--HTTP-- [맥북 print-agent] --USB--> [프린터]
 * </pre>
 *
 * {@code direct}({@link PrintAgentClient})는 백엔드가 맥북을 부른다. 그러려면
 * 맥북에 인터넷에서 닿는 고정 주소가 있어야 하고 — 접수 데스크는 공유기 뒤에 있으니
 * 터널을 열어야 한다 — 그 주소가 바뀔 때마다 EC2 의 환경변수를 고쳐야 한다.
 * 무료 터널 주소는 껐다 켤 때마다 바뀌므로, 사실상 매번 서버 설정을 만지게 된다.
 *
 * {@code queue}({@link PrintJobQueue})는 맥북이 백엔드를 부른다. 나갈 때만 연결을
 * 쓰므로 공유기·방화벽을 그대로 두고, 맥북 주소가 무엇이든 상관없다. 서버 쪽에
 * 넣을 설정이 아예 없어서 배포본을 한 번 올리고 나면 사람이 만질 것이 남지 않는다.
 * 그래서 이쪽이 기본값이다({@code print.mode=queue}).
 *
 * <h2>공통 계약</h2>
 * 어느 쪽이든 <b>실패해도 예외를 던지지 않는다</b>. 프린터는 꺼져 있을 수도, 용지가
 * 없을 수도 있는데 그때마다 접수나 증명서 발급이 막히면 진료가 멈춘다.
 * 출력은 부가 기능이므로 실패는 {@link PrintOutcome} 으로만 돌려준다.
 */
public interface PrintTransport {

    /**
     * 사람이 화면에서 '인쇄' 버튼을 눌렀을 때. 결과를 알려줘야 하므로 끝날 때까지 기다린다.
     *
     * @param path    print-agent 의 엔드포인트 경로 (예: {@code /print/ticket})
     * @param payload 그대로 JSON 으로 직렬화되어 전달될 본문
     */
    PrintOutcome send(String path, Object payload);

    /**
     * 접수·진료완료·증명서 발급에 딸려 자동으로 나가는 출력.
     * 결과를 기다리지 않는다 — 프린터가 느려도 화면이 멈추지 않도록.
     */
    void sendAsync(String path, Object payload);
}
