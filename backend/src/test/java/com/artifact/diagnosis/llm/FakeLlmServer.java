package com.artifact.diagnosis.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 테스트용 가짜 LLM 서버(Gemini·Claude 공용). 실제 HTTP 로 응답하므로, 우리 코드의 요청 조립·
 * 응답 해석·SDK 예외 처리가 운영과 같은 경로로 돈다. 외부 API 키도 네트워크도 필요 없다.
 *
 * 경로에 특정 문자열이 들어 있는 요청에 정해 둔 응답을 돌려준다. 같은 키에 응답을 여러 개 걸면
 * 순서대로 쓰고, 마지막 응답은 계속 반복한다. 받은 요청은 모두 기록한다.
 */
public class FakeLlmServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param delayMs      응답 헤더를 보내기 전에 기다리는 시간.
     * @param stallAfterMs 0 보다 크면 헤더와 본문 앞부분만 보내고 이만큼 멈춘다(본문이 멈추는 장애).
     */
    public record Reply(int status, String body, long delayMs, long stallAfterMs) {}

    public record Received(String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Deque<Reply>> scripts = new LinkedHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public FakeLlmServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 지연 응답이 다른 요청을 막지 않도록 요청마다 스레드를 쓴다.
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeLlmServer on(String pathContains, int status, String body) {
        return onDelayed(pathContains, status, body, 0);
    }

    public synchronized FakeLlmServer onDelayed(String pathContains, int status, String body, long delayMs) {
        scripts.computeIfAbsent(pathContains, k -> new ArrayDeque<>()).add(new Reply(status, body, delayMs, 0));
        return this;
    }

    /** 헤더와 본문 앞부분을 바로 보낸 뒤 본문이 멈춘다. 헤더까지만 재는 타임아웃이 이 경우를 놓친다. */
    public synchronized FakeLlmServer onStalledBody(String pathContains, int status, String body, long stallMs) {
        scripts.computeIfAbsent(pathContains, k -> new ArrayDeque<>()).add(new Reply(status, body, 0, stallMs));
        return this;
    }

    public synchronized void reset() {
        scripts.clear();
        received.clear();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> receivedFor(String pathContains) {
        return received.stream().filter(r -> r.path().contains(pathContains)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(path, query, Map.copyOf(exchange.getRequestHeaders()), body));

        Reply reply = next(path);
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(reply.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                if (reply.stallAfterMs() > 0) {
                    int head = Math.min(10, out.length);
                    os.write(out, 0, head);
                    os.flush();
                    Thread.sleep(reply.stallAfterMs());
                    os.write(out, head, out.length - head);
                } else {
                    os.write(out);
                }
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우다. 테스트가 바로 그 상황을 보는 중일 수 있다.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized Reply next(String path) {
        for (Map.Entry<String, Deque<Reply>> e : scripts.entrySet()) {
            if (!path.contains(e.getKey())) continue;
            Deque<Reply> queue = e.getValue();
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }
        return new Reply(500, "{\"error\":{\"code\":500,\"message\":\"no script for " + path + "\"}}", 0, 0);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /* ---------------- 응답 본문 도우미 ---------------- */

    /** Gemini 성공 응답. 생각(thought) 파트를 앞에 하나 끼워, 파트를 종류로 고르는지까지 본다. */
    public static String geminiText(String text) {
        return geminiResponse(text, "STOP", null);
    }

    /** 실제로 답한 모델(modelVersion)이 실린 Gemini 성공 응답. 별칭으로 불렀을 때의 모양이다. */
    public static String geminiText(String text, String modelVersion) {
        return geminiResponse(text, "STOP", modelVersion);
    }

    /** 길이 제한으로 잘린 Gemini 응답. 텍스트는 있지만 끝까지 오지 않았다. */
    public static String geminiTruncated(String partialText) {
        return geminiResponse(partialText, "MAX_TOKENS", null);
    }

    /** 텍스트와 함께 주어진 finishReason 으로 끝난 Gemini 응답. */
    public static String geminiFinished(String text, String finishReason) {
        return geminiResponse(text, finishReason, null);
    }

    private static String geminiResponse(String text, String finishReason, String modelVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(
                        Map.of("text", "생각 중", "thought", true),
                        Map.of("text", text))),
                "finishReason", finishReason)));
        if (modelVersion != null) body.put("modelVersion", modelVersion);
        return json(body);
    }

    public static String geminiError(int code, String status, String message) {
        return json(Map.of("error", Map.of("code", code, "status", status, "message", message)));
    }

    /** Claude 성공 응답. 앞에 빈 thinking 블록이 오는 실제 모양을 그대로 흉내 낸다. */
    public static String claudeText(String model, String text) {
        return claudeMessage(model, text, "end_turn");
    }

    /** max_tokens 에 걸려 잘린 Claude 응답. 텍스트는 있지만 끝까지 오지 않았다. */
    public static String claudeTruncated(String model, String partialText) {
        return claudeMessage(model, partialText, "max_tokens");
    }

    /** 텍스트와 함께 주어진 stop_reason 으로 끝난 Claude 응답. */
    public static String claudeStopped(String model, String text, String stopReason) {
        return claudeMessage(model, text, stopReason);
    }

    private static String claudeMessage(String model, String text, String stopReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "msg_test");
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", model);
        body.put("content", List.of(
                Map.of("type", "thinking", "thinking", "", "signature", "sig"),
                Map.of("type", "text", "text", text)));
        body.put("stop_reason", stopReason);
        body.put("stop_sequence", null);
        body.put("usage", Map.of("input_tokens", 10, "output_tokens", 5));
        return json(body);
    }

    public static String claudeRefusal(String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "msg_refusal");
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", model);
        body.put("content", List.of());
        body.put("stop_reason", "refusal");
        body.put("stop_sequence", null);
        body.put("usage", Map.of("input_tokens", 10, "output_tokens", 0));
        return json(body);
    }

    public static String claudeError(String type, String message) {
        return json(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
