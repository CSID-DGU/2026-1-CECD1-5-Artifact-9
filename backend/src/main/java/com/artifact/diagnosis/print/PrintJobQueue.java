package com.artifact.diagnosis.print;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 인쇄 작업 큐 — 맥북의 print-agent 가 <b>가지러 오는</b> 방식.
 *
 * <h2>왜 이 방향인가</h2>
 * 백엔드는 EC2 에 있고 프린터는 접수 데스크 맥북 USB 에 물려 있다. 서버가 맥북을
 * 부르려면 맥북에 인터넷에서 닿는 주소가 있어야 하는데, 접수 데스크는 공유기 뒤라
 * 터널을 열어야 하고 그 주소는 껐다 켤 때마다 바뀐다. 주소가 바뀔 때마다 서버
 * 환경변수를 고쳐야 하면 "설정 없이 항상 되는" 상태가 될 수 없다.
 *
 * 그래서 방향을 뒤집었다. 맥북이 서버에 접속해 "뽑을 것 있나" 를 묻는다. 나가는
 * 연결만 쓰므로 공유기·방화벽·주소와 무관하고, 서버에는 넣을 설정이 없다.
 * 맥북에서 print-agent 만 떠 있으면 동작한다.
 *
 * <pre>
 *   접수 →  enqueue()  →  [큐]  ←  GET /print/jobs/next   (맥북이 물어봄)
 *                                     ↓
 *                                  USB 출력
 *                                     ↓
 *                          POST /print/jobs/{id}/result   (결과 회신)
 * </pre>
 *
 * <h2>기다림은 톰캣 스레드를 붙잡지 않는다</h2>
 * 에이전트는 뽑을 것이 없어도 바로 끊지 않고 최대 {@code poll-wait-seconds} 동안
 * 매달려 있는다(롱 폴링). 그래야 접수가 들어온 순간 종이가 나온다. 그 시간 동안
 * 요청 스레드를 붙잡으면 프린터 하나 때문에 서버 스레드가 마르므로,
 * {@link DeferredResult} 로 스레드를 놓아주고 작업이 생겼을 때 깨운다.
 *
 * <h2>건네준 작업이 사라지지 않게 하는 법</h2>
 * 롱 폴링의 함정: 에이전트 프로세스가 죽거나 와이파이가 끊겨도 서버는 그 사실을
 * 모른다. 끊긴 연결에 {@code setResult()} 를 불러도 성공했다고 답하고, 작업은
 * 아무도 받지 못한 채 "가져감" 상태로 남는다. 실제로 에이전트를 재시작한 직후
 * 접수한 건이 이렇게 통째로 사라졌다.
 *
 * 두 겹으로 막는다.
 * <ol>
 *   <li>에이전트는 프로세스마다 새 식별자({@code X-Agent-Instance})를 들고 온다.
 *       새 식별자가 물어보러 오면, 이전 식별자로 매달려 있던 대기자는 그 자리에서
 *       끊는다 — 재시작 직후의 유령 연결이 작업을 삼키지 못한다.</li>
 *   <li>그래도 건네준 뒤 {@code visibility-timeout-seconds} 동안 회신이 없으면
 *       큐로 되돌려 다시 건넨다. 출력은 길어야 몇 초라 그보다 오래 조용하다는 것은
 *       사실상 받지 못했다는 뜻이다. 되돌린 뒤 진짜로는 출력됐던 경우 한 장이 더
 *       나올 수 있는데, 접수증이 안 나오는 쪽이 더 나쁘므로 이 방향을 택했다.</li>
 * </ol>
 *
 * <h2>왜 작업이 늙으면 버리는가</h2>
 * 프린터가 꺼진 채로 접수가 열 건 쌓였다가 저녁에 프린터를 켜면, 그때 열 장이
 * 한꺼번에 나온다. 이미 진료가 끝난 사람의 대기번호표라 쓸모가 없고 오히려
 * 혼란스럽다. 그래서 {@code job-ttl-seconds} 가 지난 작업은 조용히 버린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "print.mode", havingValue = "queue", matchIfMissing = true)
public class PrintJobQueue implements PrintTransport {

    /** 프린터를 안 쓰는 환경에서 통째로 끄는 스위치. {@link PrintAgentClient} 와 같은 값을 본다. */
    @Value("${print.agent.enabled:true}")
    private boolean enabled;

    /** 이 시간이 지난 작업은 뽑지 않고 버린다. 위 "왜 작업이 늙으면 버리는가" 참고. */
    @Value("${print.queue.job-ttl-seconds:120}")
    private long jobTtlSeconds;

    /** 큐에 쌓아둘 최대 작업 수. 넘치면 가장 오래된 것부터 버린다. */
    @Value("${print.queue.capacity:20}")
    private int capacity;

    /** 화면의 '인쇄' 버튼이 결과를 기다리는 최대 시간. */
    @Value("${print.queue.wait-seconds:15}")
    private long waitSeconds;

    /** 에이전트가 살아있다고 볼 수 있는 마지막 폴링 이후 시간. */
    @Value("${print.queue.agent-timeout-seconds:90}")
    private long agentTimeoutSeconds;

    /**
     * 작업을 건네준 뒤 회신을 기다리는 시간. 넘기면 큐로 되돌린다.
     * 위 "건네준 작업이 사라지지 않게 하는 법" 참고.
     */
    @Value("${print.queue.visibility-timeout-seconds:20}")
    private long visibilityTimeoutSeconds;

    /** 한 작업을 몇 번까지 다시 건네줄지. 넘으면 실패로 확정한다. */
    @Value("${print.queue.max-attempts:2}")
    private int maxAttempts;

    /**
     * pending·waiters 를 함께 지키는 자물쇠.
     *
     * 둘을 각각 동시성 컬렉션으로 두면 "작업을 넣는 순간 마지막 대기자가 사라지는"
     * 창이 생겨 작업이 큐에도 없고 누구에게도 전달되지 않은 채 사라질 수 있다.
     * 인쇄는 초당 몇 건 규모라 통째로 잠가도 비용이 없다.
     */
    private final Object lock = new Object();

    private final Deque<PrintJob> pending = new ArrayDeque<>();
    private final Deque<Waiter> waiters = new ArrayDeque<>();

    /** 에이전트가 가져갔지만 아직 결과를 안 준 작업. */
    private final Map<String, Lease> inFlight = new ConcurrentHashMap<>();

    /** 에이전트가 마지막으로 물어본 시각. 살아있는지 판단하는 유일한 근거다. */
    private volatile Instant lastPollAt;

    /** 회신 없는 작업을 폴링과 무관하게 되돌리기 위한 타이머. */
    private ScheduledExecutorService sweeper;

    /**
     * 큐에 들어와 아직 결과가 정해지지 않은 작업 하나.
     *
     * {@code attempts} 만 가변인 이유: 다시 건넬 때마다 record 를 새로 만들면 이미
     * 결과를 기다리고 있는 쪽의 future 가 끊긴다. 세는 값 하나만 안에서 움직인다.
     */
    private record PrintJob(String id, String docType, Object payload,
                            Instant createdAt, CompletableFuture<PrintOutcome> result,
                            AtomicInteger attempts) {
    }

    /** 에이전트에게 건네준 작업과, 건네준 시각. */
    private record Lease(PrintJob job, Instant handedAt) {
    }

    /**
     * 롱 폴링으로 매달려 있는 에이전트 하나.
     *
     * {@code instance} 는 에이전트 프로세스마다 새로 만들어지는 값이다. 이 값이
     * 바뀌었다는 것은 저쪽이 재시작했다는 뜻이고, 곧 이전 값으로 매달린 연결은
     * 이미 끊어졌다는 뜻이다.
     */
    private record Waiter(DeferredResult<ResponseEntity<PrintJobMessages.Envelope>> deferred,
                          String instance) {
    }

    // ── PrintTransport ──────────────────────────────────────────────────────

    @Override
    public PrintOutcome send(String path, Object payload) {
        if (!enabled) {
            return PrintOutcome.failure("감열지 출력이 꺼져 있습니다 (print.agent.enabled=false).");
        }
        // 에이전트가 붙어 있지 않으면 기다릴 이유가 없다. 15초를 세고 나서 실패를
        // 알리는 것보다, 즉시 "연결 안 됨" 이라고 말하는 편이 정확하고 빠르다.
        if (!agentConnected()) {
            return PrintOutcome.failure(
                    "프린터가 연결되어 있지 않습니다 — 접수 데스크 맥북에서 print-agent 가 "
                  + "켜져 있는지 확인하세요.");
        }

        PrintJob job = enqueue(path, payload);
        try {
            return job.result().get(waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancelIfPending(job);
            return PrintOutcome.failure("프린터가 제때 응답하지 않았습니다. 용지와 전원을 확인하세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelIfPending(job);
            return PrintOutcome.failure("출력 대기가 중단됐습니다.");
        } catch (ExecutionException e) {
            log.warn("인쇄 작업 처리 중 오류 {}: {}", job.docType(), e.toString());
            return PrintOutcome.failure("출력 처리 중 오류가 발생했습니다.");
        }
    }

    @Override
    public void sendAsync(String path, Object payload) {
        if (!enabled) {
            return;
        }
        // 결과를 볼 사람이 없으므로 넣기만 하고 잊는다. 에이전트가 꺼져 있으면
        // job-ttl-seconds 뒤에 조용히 사라진다.
        PrintJob job = enqueue(path, payload);
        job.result().thenAccept(outcome -> {
            if (!outcome.ok()) {
                log.warn("자동 출력 실패 [{}]: {}", job.docType(), outcome.detail());
            }
        });
    }

    // ── 에이전트 쪽 (PrintJobController 가 부른다) ─────────────────────────────

    /**
     * "뽑을 것 있나?" 에 답한다. 지금 없으면 생길 때까지 매달아 둔다.
     *
     * @param waitMillis 이 시간까지 아무것도 없으면 204 로 끊는다. 에이전트는 곧바로 다시 묻는다.
     */
    public DeferredResult<ResponseEntity<PrintJobMessages.Envelope>> nextJob(long waitMillis,
                                                                            String agentInstance) {
        lastPollAt = Instant.now();

        var deferred = new DeferredResult<ResponseEntity<PrintJobMessages.Envelope>>(
                waitMillis, () -> ResponseEntity.noContent().build());
        String instance = (agentInstance == null || agentInstance.isBlank())
                ? "unknown" : agentInstance.trim();

        synchronized (lock) {
            evictOtherInstances(instance);
            sweepExpired();
            // 대기자를 먼저 등록한 뒤 큐를 비운다. 반대로 하면 큐가 빈 것을 확인한
            // 직후 들어온 작업이 아직 없는 대기자를 못 찾고 큐로 들어가, 이 요청은
            // 빈손으로 204 를 받고 그 작업은 다음 폴링까지 기다리게 된다.
            Waiter waiter = new Waiter(deferred, instance);
            waiters.addLast(waiter);
            deferred.onCompletion(() -> {
                synchronized (lock) {
                    waiters.remove(waiter);
                }
            });
            drain();
        }
        return deferred;
    }

    /**
     * 다른 프로세스 이름표를 달고 매달려 있는 대기자를 끊는다. 반드시 {@code lock} 안에서 부른다.
     *
     * 에이전트는 한 대뿐이므로 이름표가 바뀌었다면 앞의 것은 죽은 연결이다. 두면
     * 자기 타임아웃(최대 25초)까지 살아 있는 척하면서 그 사이 들어온 작업을 삼킨다.
     */
    private void evictOtherInstances(String instance) {
        if ("unknown".equals(instance)) {
            return; // 이름표를 안 붙이는 구버전 에이전트다. 함부로 남을 끊지 않는다.
        }
        for (Waiter waiter : List.copyOf(waiters)) {
            if (!instance.equals(waiter.instance())) {
                log.info("이전 에이전트({})의 연결을 정리한다 — 새 프로세스({})가 붙었다",
                        waiter.instance(), instance);
                waiter.deferred().setResult(ResponseEntity.noContent().build());
                waiters.remove(waiter);
            }
        }
    }

    /**
     * 에이전트가 출력 결과를 돌려준다.
     *
     * @return 모르는 jobId 면 false — 이미 만료됐거나 다른 서버 인스턴스의 작업이다.
     */
    public boolean report(String jobId, boolean ok, String detail) {
        Lease lease = inFlight.remove(jobId);
        if (lease == null) {
            return false;
        }
        PrintJob job = lease.job();
        job.result().complete(ok
                ? PrintOutcome.success()
                : PrintOutcome.failure(detail == null || detail.isBlank() ? "출력에 실패했습니다." : detail));
        return true;
    }

    /** 운영자가 "프린터 붙어 있나" 를 눈으로 확인하는 용도. */
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", "queue");
        map.put("enabled", enabled);
        map.put("agentConnected", agentConnected());
        map.put("lastPollAt", lastPollAt == null ? null : lastPollAt.toString());
        synchronized (lock) {
            map.put("pending", pending.size());
            map.put("waitingAgents", waiters.size());
        }
        map.put("inFlight", inFlight.size());
        return map;
    }

    /** 마지막 폴링이 충분히 최근이면 에이전트가 살아 있다고 본다. */
    private boolean agentConnected() {
        Instant seen = lastPollAt;
        return seen != null && Duration.between(seen, Instant.now()).getSeconds() < agentTimeoutSeconds;
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private PrintJob enqueue(String path, Object payload) {
        PrintJob job = new PrintJob(
                UUID.randomUUID().toString(),
                docTypeOf(path),
                payload,
                Instant.now(),
                new CompletableFuture<>(),
                new AtomicInteger());

        synchronized (lock) {
            sweepExpired();
            pending.addLast(job);
            while (pending.size() > Math.max(1, capacity)) {
                PrintJob dropped = pending.pollFirst();
                if (dropped != null) {
                    log.warn("인쇄 큐가 가득 차 가장 오래된 작업을 버린다 [{}]", dropped.docType());
                    dropped.result().complete(PrintOutcome.failure("인쇄 대기열이 가득 차 취소됐습니다."));
                }
            }
            drain();
        }
        return job;
    }

    /**
     * 큐에 쌓인 작업을 매달려 있는 에이전트에게 넘긴다. 반드시 {@code lock} 안에서 부른다.
     */
    private void drain() {
        while (!pending.isEmpty() && !waiters.isEmpty()) {
            PrintJob job = pending.peekFirst();
            Waiter waiter = waiters.pollFirst();
            if (waiter == null || waiter.deferred().isSetOrExpired()) {
                continue; // 이미 끊긴 대기자다. 작업은 큐에 그대로 둔다.
            }

            inFlight.put(job.id(), new Lease(job, Instant.now()));
            boolean handed = waiter.deferred().setResult(ResponseEntity.ok(
                    new PrintJobMessages.Envelope(job.id(), job.docType(), job.payload())));
            if (!handed) {
                // 넘기려는 찰나에 타임아웃으로 끊겼다. 작업은 큐에 남겨 다음 폴링에 넘긴다.
                inFlight.remove(job.id());
                continue;
            }
            // setResult 가 true 라고 해서 저쪽이 받았다는 뜻은 아니다 — 끊긴 소켓에도
            // true 가 나온다. 그래서 여기서 회차를 세고, sweepExpired 가 회신을 감시한다.
            job.attempts().incrementAndGet();
            pending.pollFirst();
        }
    }

    /** 늙은 작업과, 가져가 놓고 결과를 주지 않은 작업을 치운다. 반드시 {@code lock} 안에서 부른다. */
    private void sweepExpired() {
        Instant deadline = Instant.now().minusSeconds(Math.max(1, jobTtlSeconds));

        pending.removeIf(job -> {
            if (job.createdAt().isAfter(deadline)) {
                return false;
            }
            log.warn("인쇄 작업이 만료돼 버린다 [{}] — 프린터가 꺼져 있었을 가능성", job.docType());
            job.result().complete(PrintOutcome.failure(
                    "프린터가 응답하지 않아 출력이 취소됐습니다 (대기 시간 초과)."));
            return true;
        });

        // 가져가 놓고 조용한 작업. 출력은 길어야 몇 초라, 이만큼 지났으면 애초에
        // 저쪽에 닿지 않은 것으로 본다. 아직 늙지 않았으면 큐로 되돌려 다시 건넨다.
        Instant handoverDeadline = Instant.now().minusSeconds(Math.max(1, visibilityTimeoutSeconds));
        List<String> stale = new ArrayList<>();
        inFlight.forEach((id, lease) -> {
            if (lease.handedAt().isBefore(handoverDeadline)) {
                stale.add(id);
            }
        });
        for (String id : stale) {
            Lease lease = inFlight.remove(id);
            if (lease == null) {
                continue;
            }
            PrintJob job = lease.job();
            boolean tooOld = job.createdAt().isBefore(deadline);
            if (tooOld || job.attempts().get() >= Math.max(1, maxAttempts)) {
                log.warn("회신 없는 인쇄 작업을 포기한다 [{}] (시도 {}회)",
                        job.docType(), job.attempts().get());
                job.result().complete(PrintOutcome.failure("프린터에서 결과 회신이 없었습니다."));
                continue;
            }
            log.warn("회신 없는 인쇄 작업을 큐로 되돌린다 [{}] (시도 {}회) — 연결이 끊겼을 가능성",
                    job.docType(), job.attempts().get());
            pending.addFirst(job); // 원래 순서를 지키려고 맨 앞에 넣는다
        }
        if (!stale.isEmpty()) {
            drain();
        }
    }

    // ── 되돌리기 타이머 ──────────────────────────────────────────────────────

    /**
     * 폴링이 없어도 되돌리기가 도는 이유.
     *
     * sweepExpired 를 폴링과 접수에만 걸어 두면, 연결이 끊긴 순간부터 다음 폴링까지
     * 최대 25초를 그냥 흘려보낸 뒤에야 되돌리기가 시작된다. 접수증은 그 사이에
     * 나와야 하는 종이다. 스레드 하나짜리 타이머로 몇 초마다 들여다본다.
     */
    @PostConstruct
    void startSweeper() {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "print-queue-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                synchronized (lock) {
                    sweepExpired();
                }
            } catch (RuntimeException e) {
                log.warn("인쇄 큐 정리 중 오류: {}", e.toString());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopSweeper() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    private void cancelIfPending(PrintJob job) {
        synchronized (lock) {
            pending.removeIf(j -> j.id().equals(job.id()));
        }
    }

    /**
     * {@code /print/ticket} 처럼 생긴 경로에서 문서 종류만 뽑는다.
     *
     * 호출부({@link PrintService})는 지금까지 쓰던 경로 문자열을 그대로 넘긴다.
     * 통로를 바꾸려고 호출부 여섯 군데를 고치면, 나중에 direct 로 되돌릴 때 또 고쳐야 한다.
     */
    private static String docTypeOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
