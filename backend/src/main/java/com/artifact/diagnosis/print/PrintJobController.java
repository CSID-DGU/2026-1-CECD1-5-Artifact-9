package com.artifact.diagnosis.print;

import com.artifact.diagnosis.common.security.StaffAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;

/**
 * 맥북의 print-agent 가 인쇄 작업을 가져가고 결과를 돌려주는 창구.
 *
 * <h2>이 컨트롤러의 손님은 사람이 아니다</h2>
 * 브라우저가 아니라 접수 데스크 맥북에서 도는 {@code print-agent} 가 부른다.
 * 그쪽은 직원 계정으로 로그인해 받은 JWT 를 그대로 쓴다 — 프린터 전용 인증 체계를
 * 따로 만들지 않은 이유는, 새로 만들면 그 자체가 관리할 비밀 하나가 더 늘어나고
 * 서버에도 넣을 설정이 생겨 "설정 없이 도는" 목표가 깨지기 때문이다.
 *
 * <h2>권한</h2>
 * {@link StaffAccess} 다. 인쇄 작업의 본문에는 환자 이름과 상병명이 들어 있어
 * 로그인 없이 열 수 없고, 이미 접수 화면을 볼 수 있는 직책이면 같은 정보를 화면에서도
 * 본다. 다만 이 창구로 가져간 JWT 는 다른 API 에도 쓸 수 있는 값이므로,
 * 에이전트 전용 계정을 따로 만들어 쓰고 그 자격은 맥북 안({@code print-agent/.env})에만 둔다.
 *
 * <h2>왜 오래 매달려 있게 두는가</h2>
 * {@code /next} 는 뽑을 것이 없어도 바로 끊지 않고 최대 {@code poll-wait-seconds}
 * 동안 기다린다. 1초마다 다시 묻게 하면 접수 한 건에 종이 나오는 시간이 최대 1초
 * 늦고 요청 수는 하루 수만 건이 된다. 매달아 두면 접수와 동시에 종이가 나오고
 * 요청 수는 분당 두어 건이다. 기다리는 동안 서버 스레드는 붙잡지 않는다
 * ({@link PrintJobQueue} 의 DeferredResult 설명 참고).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/print/jobs")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "print.mode", havingValue = "queue", matchIfMissing = true)
public class PrintJobController {

    private final PrintJobQueue queue;

    /**
     * 롱 폴링이 매달려 있는 시간.
     *
     * 톰캣의 비동기 요청 타임아웃(기본 30초)보다 짧아야 한다. 넘기면 서버가
     * 500 을 먼저 뱉어 에이전트 로그가 오류로 뒤덮인다.
     */
    @Value("${print.queue.poll-wait-seconds:25}")
    private long pollWaitSeconds;

    /**
     * "뽑을 것 있나?" — 있으면 200 + 작업, 없으면 204.
     *
     * {@code X-Agent-Instance} 는 에이전트 프로세스마다 새로 만드는 식별자다. 이 값이
     * 바뀌면 저쪽이 재시작한 것이고, 앞선 연결은 이미 끊어졌다는 뜻이므로 서버가
     * 그 유령 대기자를 걷어낸다(없으면 걷어내지 않으니 구버전 에이전트도 그대로 돈다).
     */
    @StaffAccess
    @GetMapping("/next")
    public DeferredResult<ResponseEntity<PrintJobMessages.Envelope>> next(
            @RequestHeader(name = "X-Agent-Instance", required = false) String agentInstance) {
        return queue.nextJob(pollWaitSeconds * 1000L, agentInstance);
    }

    /**
     * "뽑았다 / 못 뽑았다" 회신.
     *
     * 모르는 jobId 여도 200 을 준다. 그런 일은 작업이 만료된 뒤 늦게 회신했을 때
     * 생기는데, 에이전트가 할 수 있는 일이 없으므로 오류로 만들 이유가 없다.
     */
    @StaffAccess
    @PostMapping("/{jobId}/result")
    public Map<String, Object> report(@PathVariable String jobId,
                                      @RequestBody PrintJobMessages.Report body) {
        boolean matched = queue.report(jobId, body.ok(), body.detail());
        if (!matched) {
            log.debug("이미 만료된 인쇄 작업의 결과가 도착했다 (jobId={})", jobId);
        }
        return Map.of("ok", true, "matched", matched);
    }

    /** 프린터가 붙어 있는지 눈으로 확인하는 용도. 화면이나 curl 에서 부른다. */
    @StaffAccess
    @GetMapping("/status")
    public Map<String, Object> status() {
        return queue.status();
    }
}
