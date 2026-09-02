package com.artifact.diagnosis.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 맥 호스트에서 도는 print-agent 호출기.
 *
 * <h2>왜 별도 프로세스인가</h2>
 * Docker Desktop for Mac 은 USB 패스스루를 지원하지 않는다. 컨테이너 안의 이
 * 백엔드는 감열지 프린터에 직접 접근할 방법이 없어서, 프린터가 물린 맥 호스트에
 * 작은 HTTP 서비스를 따로 띄우고 그쪽으로 넘긴다.
 *
 * <pre>
 *   [백엔드 컨테이너] --HTTP--> [print-agent (맥 호스트)] --USB--> [프린터]
 * </pre>
 *
 * <h2>왜 실패해도 예외를 던지지 않는가</h2>
 * 프린터는 꺼져 있을 수도, 용지가 떨어졌을 수도, 아예 안 물려 있을 수도 있다.
 * 그때마다 접수나 증명서 발급이 실패하면 진료가 멈춘다. 출력은 부가 기능이므로
 * 실패는 {@code log.warn} 으로만 남기고 본 흐름은 그대로 진행한다.
 *
 * <h2>왜 자동 출력은 비동기인가</h2>
 * 프린터가 응답하지 않으면 타임아웃까지 기다리게 되는데, 그 시간만큼 접수 화면이
 * 멈춘다. 자동 출력은 결과를 볼 사람이 없으므로 별도 스레드에 던지고 즉시 반환한다.
 * 프린터는 한 대뿐이라 스레드도 하나만 둔다 — 출력 순서가 섞이지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrintAgentClient {

    /** 요청마다 새로 만들지 않고 공유한다 — {@code HttpClientConfig} 참고. 연결 타임아웃이 걸려 있다. */
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${print.agent.url:http://host.docker.internal:5051}")
    private String agentUrl;

    /** 프린터를 안 쓰는 환경(CI, 원격 서버)에서 통째로 끄는 스위치. */
    @Value("${print.agent.enabled:true}")
    private boolean enabled;

    /**
     * 터널 너머의 에이전트를 부를 때 쓰는 인증 토큰.
     *
     * 맥북 안에서만 도는 기본 구성(host.docker.internal)에서는 비워둔다. 백엔드를
     * EC2 에 두고 프린터를 접수 데스크 맥북에 두면 그 사이를 터널로 잇게 되는데,
     * 그러면 에이전트가 공개 HTTPS 주소에 노출된다. 그 상태에서 토큰이 없으면
     * 주소를 아는 누구나 병원 프린터로 종이를 뽑을 수 있다.
     * print-agent 의 {@code AGENT_TOKEN} 과 같은 값이어야 한다.
     */
    @Value("${print.agent.token:}")
    private String agentToken;

    /** 도장 이미지가 들어가는 발급확인증은 전송량이 있어 넉넉하게 잡는다. */
    @Value("${print.agent.timeout-seconds:10}")
    private long timeoutSeconds;

    private final ExecutorService printExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "print-agent");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    void shutdown() {
        printExecutor.shutdown();
        try {
            if (!printExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                printExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            printExecutor.shutdownNow();
        }
    }

    /**
     * 사람이 '인쇄' 버튼을 눌렀을 때. 결과를 알려줘야 하므로 끝날 때까지 기다린다.
     */
    public PrintOutcome send(String path, Object payload) {
        if (!enabled) {
            return PrintOutcome.failure("감열지 출력이 꺼져 있습니다 (print.agent.enabled=false).");
        }
        String token = agentToken == null ? "" : agentToken.trim();
        if (!token.isEmpty() && !isHeaderSafe(token)) {
            log.warn("print.agent.token 에 HTTP 헤더로 쓸 수 없는 문자가 있어 출력을 건너뛴다 (길이={})", token.length());
            return PrintOutcome.failure(
                    "프린터 인증 토큰 설정이 잘못됐습니다 — PRINT_AGENT_TOKEN 에 한글이나 줄바꿈 같은 "
                  + "문자가 섞이지 않았는지 확인하세요.");
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(agentUrl + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (!token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                log.debug("감열지 출력 성공 {} -> {}", path, response.body());
                return PrintOutcome.success();
            }
            log.warn("감열지 출력 실패 {} (status={}): {}", path, response.statusCode(), response.body());
            return PrintOutcome.failure(describe(response.statusCode(), response.body()));

        } catch (Exception e) {
            // 프린터/에이전트가 죽어 있어도 여기서 끝난다. 호출한 쪽은 계속 진행한다.
            log.warn("print-agent 호출 실패 {} ({}): {}", path, agentUrl, e.toString());
            return PrintOutcome.failure("프린터 서비스에 연결하지 못했습니다 — print-agent 가 켜져 있는지 확인하세요.");
        }
    }

    /**
     * 접수·진료완료·증명서 발급처럼 자동으로 딸려 나가는 출력.
     * 결과를 기다리지 않는다 — 프린터가 느려도 화면이 멈추지 않도록.
     */
    public void sendAsync(String path, Object payload) {
        if (!enabled) {
            return;
        }
        try {
            printExecutor.execute(() -> send(path, payload));
        } catch (Exception e) {
            log.warn("감열지 출력 작업을 큐에 넣지 못했습니다 {}: {}", path, e.toString());
        }
    }

    /**
     * 에이전트가 돌려준 JSON 의 detail 을 꺼낸다. 못 꺼내면 원문을 그대로 쓴다.
     *
     * 401 만 따로 다룬다 — 에이전트가 주는 "인증 토큰이 필요합니다" 는 화면에
     * 그대로 띄우면 접수 담당자가 무엇을 해야 할지 알 수 없는 문구다. 실제 원인은
     * 양쪽 토큰이 어긋난 것이므로 그렇게 알려준다.
     */
    /**
     * 토큰을 그대로 헤더에 실어도 되는지 본다.
     *
     * Java 의 {@link HttpRequest.Builder#header} 는 헤더 값에 가시 ASCII 만 받는다.
     * 한글이나 줄바꿈이 섞이면 요청을 만들다가 {@code IllegalArgumentException} 이
     * 나는데, 그게 아래 {@code catch (Exception)} 에 잡히면 "print-agent 가 켜져
     * 있는지 확인하세요" 라는 엉뚱한 안내가 나간다. 실제 원인은 설정값인데 프린터
     * 전원을 확인하러 가게 되므로, 부르기 전에 걸러서 정확한 사유를 돌려준다.
     *
     * {@code .env} 값을 옮기다 앞뒤 공백이 딸려오는 일은 흔하므로 그건 호출부에서
     * {@code trim} 으로 조용히 복구하고, 여기서는 남은 문자만 본다.
     */
    private boolean isHeaderSafe(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private String describe(int status, String body) {
        if (status == 401 || status == 403) {
            return "프린터 서비스 인증에 실패했습니다 — 백엔드의 PRINT_AGENT_TOKEN 과 "
                 + "print-agent 의 AGENT_TOKEN 이 같은 값인지 확인하세요.";
        }
        try {
            var node = objectMapper.readTree(body).get("detail");
            if (node != null && !node.isNull()) {
                return node.isTextual() ? node.asText() : node.toString();
            }
        } catch (Exception ignored) {
            // 파싱 실패는 아래 기본 문구로
        }
        return "프린터 오류 (HTTP " + status + ")";
    }
}
