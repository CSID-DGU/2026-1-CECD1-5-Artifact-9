package com.artifact.diagnosis.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 외부 API(FastAPI 추론 서버, Gemini) 호출용 공용 {@link HttpClient}.
 *
 * 왜 빈으로 두는가. 이전에는 호출할 때마다 {@code HttpClient.newHttpClient()} 로
 * 새 클라이언트를 만들었다. {@code HttpClient} 는 내부에 셀렉터 스레드와 커넥션 풀을 들고 있어서,
 * 요청마다 새로 만들면 연결을 재사용하지 못하고 스레드도 계속 쌓인다(GC 전까지 회수되지 않는다).
 * 하나만 만들어 공유하는 것이 이 클래스의 원래 사용법이다 — 스레드 안전하다.
 *
 * 왜 타임아웃이 필요한가. 기본값은 "무한 대기"다. 상대 서버가 죽지 않고 멈추기만 하면
 * (추론 중 GPU 대기, 네트워크 블랙홀) 요청 스레드가 영원히 묶인다. 그 스레드가 DB 트랜잭션까지
 * 들고 있으면 커넥션 풀이 말라 분석과 무관한 접수·조회 화면까지 함께 멈춘다.
 * 여기서는 연결 타임아웃만 걸고, 응답 타임아웃은 호출처마다 성격이 달라
 * 각 {@code HttpRequest} 에서 {@code .timeout(...)} 으로 지정한다(FastAPI 추론 30초, Gemini 15초).
 *
 * HTTP/1.1로 고정한 이유: FastAPI(uvicorn)는 평문 HTTP/2를 받지 않는다.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient externalHttpClient(
            @Value("${http.client.connect-timeout-seconds:5}") long connectTimeoutSeconds) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }
}
