package com.artifact.diagnosis.print;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * 접수증 QR 에 찍을 키오스크 base URL 을 검사한다.
 *
 * <h2>왜 클라이언트가 보낸 주소를 그대로 쓰지 않는가</h2>
 * 접수 화면은 자기가 실제로 쓰는 키오스크 주소를 {@code X-Kiosk-Base-Url} 헤더로
 * 보낸다. 화면에 뜬 QR 과 종이에 찍힌 QR 이 다른 곳을 가리키면 환자가 엉뚱한
 * 주소로 이동하기 때문이다.
 *
 * 그런데 이 값은 곧 <b>종이에 인쇄되는 QR</b> 이 된다. QR 은 사람이 눈으로 내용을
 * 읽을 수 없어서, 엉뚱한 주소가 섞여도 종이만 봐서는 알아챌 수 없다. 접수 권한을
 * 가진 계정이 탈취되면 "병원이 발행한 종이" 를 통해 피싱 사이트로 환자를 보낼 수
 * 있다는 뜻이다. 그래서 형식을 검사하고, 운영 환경에서는 허용 목록으로 잠근다.
 *
 * <h2>거부하면 어떻게 되는가</h2>
 * 예외를 던지지 않고 {@code null} 을 돌려준다. 그러면 print-agent 가 자기
 * {@code KIOSK_BASE_URL} 기본값을 쓴다 — 접수 자체가 실패하는 것보다, 종이가
 * 기본 주소로 나가고 경고 로그가 남는 편이 낫다.
 */
@Slf4j
@Component
public class KioskBaseUrlPolicy {

    /**
     * QR 에 들어갈 문자열이 길어질수록 모듈 수가 늘어 감열지에서 읽기 어려워진다.
     * base URL 뒤에 {@code /kiosk/{토큰}} 이 더 붙는다는 점까지 감안한 상한이다.
     */
    private static final int MAX_LENGTH = 200;

    /**
     * 허용할 base URL 목록(콤마 구분). 비워두면 형식 검사만 하고 통과시킨다.
     *
     * 로컬 개발에서는 접속 주소가 매번 달라서(localhost, 맥북 LAN IP, 터널 주소)
     * 목록을 고정할 수 없다. 그래서 기본값은 비워두고, 운영 환경의
     * {@code docker-compose.prod.yml} 에서만 실제 도메인으로 잠근다.
     */
    @Value("${print.kiosk.allowed-base-urls:}")
    private String allowedRaw;

    /**
     * 헤더로 받은 값을 정규화해서 돌려준다. 쓸 수 없는 값이면 {@code null}.
     *
     * @param requested 접수 화면이 보낸 base URL. null 이거나 빈 문자열이어도 된다.
     */
    public String sanitize(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String raw = requested.trim();
        if (raw.length() > MAX_LENGTH) {
            log.warn("키오스크 base URL 이 너무 길어 무시한다 ({}자)", raw.length());
            return null;
        }

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            log.warn("키오스크 base URL 을 해석하지 못해 무시한다: {}", raw);
            return null;
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // javascript:, data: 처럼 QR 로 찍히면 위험한 스킴을 여기서 잘라낸다.
            log.warn("키오스크 base URL 의 스킴이 http/https 가 아니라 무시한다: {}", raw);
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            log.warn("키오스크 base URL 에 호스트가 없어 무시한다: {}", raw);
            return null;
        }

        String normalized = normalize(uri);
        List<String> allowed = allowedList();
        if (!allowed.isEmpty() && !allowed.contains(normalized)) {
            log.warn("허용 목록에 없는 키오스크 base URL 이라 무시한다: {} (허용: {})", normalized, allowed);
            return null;
        }
        return normalized;
    }

    /**
     * 스킴·호스트·포트·경로만 남긴다.
     *
     * 쿼리스트링과 프래그먼트는 버린다. 이 값 뒤에 {@code /kiosk/{토큰}} 을 붙여
     * QR 을 만들기 때문에, {@code ?a=b} 가 남아 있으면 경로가 깨진다.
     */
    private static String normalize(URI uri) {
        StringBuilder sb = new StringBuilder()
                .append(uri.getScheme().toLowerCase())
                .append("://")
                .append(uri.getHost().toLowerCase());
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            sb.append(path.replaceAll("/+$", ""));
        }
        return sb.toString();
    }

    private List<String> allowedList() {
        if (allowedRaw == null || allowedRaw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return normalize(URI.create(s));
                    } catch (RuntimeException e) {
                        log.warn("print.kiosk.allowed-base-urls 의 값을 해석하지 못했다: {}", s);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
