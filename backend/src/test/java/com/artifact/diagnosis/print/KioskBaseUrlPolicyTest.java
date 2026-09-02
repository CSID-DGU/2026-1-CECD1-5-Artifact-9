package com.artifact.diagnosis.print;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접수증 QR 에 찍힐 주소의 검사 규칙을 고정한다.
 *
 * 이 값은 종이에 QR 로 인쇄된다. QR 은 사람이 눈으로 내용을 읽을 수 없어서, 엉뚱한
 * 주소가 섞여도 종이만 봐서는 알아챌 방법이 없다. 접수 권한을 가진 계정이 탈취되면
 * "병원이 발행한 종이" 를 통해 환자를 피싱 사이트로 보낼 수 있다는 뜻이라, 무엇을
 * 통과시키고 무엇을 막는지가 화면 문구보다 중요하다.
 */
class KioskBaseUrlPolicyTest {

    /** 허용 목록을 비워 둔 상태 — 로컬 개발 기본값. 형식 검사만 한다. */
    private static KioskBaseUrlPolicy openPolicy() {
        return policyWith("");
    }

    private static KioskBaseUrlPolicy policyWith(String allowed) {
        KioskBaseUrlPolicy policy = new KioskBaseUrlPolicy();
        ReflectionTestUtils.setField(policy, "allowedRaw", allowed);
        return policy;
    }

    @Test
    @DisplayName("값이 없으면 null — print-agent 가 자기 기본 주소를 쓴다")
    void blankFallsBackToAgentDefault() {
        assertThat(openPolicy().sanitize(null)).isNull();
        assertThat(openPolicy().sanitize("")).isNull();
        assertThat(openPolicy().sanitize("   ")).isNull();
    }

    @Test
    @DisplayName("정상 주소는 그대로 통과한다 — 배포 도메인, 맥북 LAN IP, localhost")
    void acceptsNormalAddresses() {
        KioskBaseUrlPolicy policy = openPolicy();
        assertThat(policy.sanitize("https://artifact-prod.duckdns.org"))
                .isEqualTo("https://artifact-prod.duckdns.org");
        assertThat(policy.sanitize("http://192.168.0.12:3000"))
                .isEqualTo("http://192.168.0.12:3000");
        assertThat(policy.sanitize("http://localhost:3000"))
                .isEqualTo("http://localhost:3000");
    }

    @Test
    @DisplayName("끝 슬래시와 앞뒤 공백은 지운다 — 뒤에 /kiosk/{토큰} 이 붙기 때문")
    void normalizesTrailingSlash() {
        assertThat(openPolicy().sanitize("  https://example.com/  ")).isEqualTo("https://example.com");
        assertThat(openPolicy().sanitize("https://example.com///")).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("쿼리스트링과 프래그먼트는 버린다 — 남으면 QR 경로가 깨진다")
    void dropsQueryAndFragment() {
        assertThat(openPolicy().sanitize("https://example.com/?a=b")).isEqualTo("https://example.com");
        assertThat(openPolicy().sanitize("https://example.com/base#x")).isEqualTo("https://example.com/base");
    }

    @Test
    @DisplayName("http/https 가 아닌 스킴은 막는다 — QR 로 찍히면 그대로 실행될 수 있다")
    void rejectsDangerousSchemes() {
        KioskBaseUrlPolicy policy = openPolicy();
        assertThat(policy.sanitize("javascript:alert(1)")).isNull();
        assertThat(policy.sanitize("data:text/html,<script>x</script>")).isNull();
        assertThat(policy.sanitize("file:///etc/passwd")).isNull();
        assertThat(policy.sanitize("ftp://example.com")).isNull();
    }

    @Test
    @DisplayName("스킴이나 호스트가 없으면 막는다")
    void rejectsMalformed() {
        KioskBaseUrlPolicy policy = openPolicy();
        assertThat(policy.sanitize("example.com")).isNull();      // 스킴 없음
        assertThat(policy.sanitize("https://")).isNull();          // 호스트 없음
        assertThat(policy.sanitize("http:// space")).isNull();      // 해석 불가
    }

    @Test
    @DisplayName("QR 이 읽히지 않을 만큼 긴 주소는 막는다")
    void rejectsOverlyLong() {
        String tooLong = "https://example.com/" + "x".repeat(300);
        assertThat(openPolicy().sanitize(tooLong)).isNull();
    }

    @Test
    @DisplayName("허용 목록을 두면 목록 밖 주소는 막는다 — 운영 설정")
    void enforcesAllowList() {
        KioskBaseUrlPolicy policy = policyWith("https://artifact-prod.duckdns.org");
        assertThat(policy.sanitize("https://artifact-prod.duckdns.org"))
                .isEqualTo("https://artifact-prod.duckdns.org");
        // 형식은 멀쩡하지만 우리 도메인이 아니다. 이게 막고 싶은 바로 그 경우다.
        assertThat(policy.sanitize("https://evil.example.com")).isNull();
    }

    @Test
    @DisplayName("허용 목록도 정규화해서 비교한다 — 설정에 끝 슬래시가 붙어도 동작해야 한다")
    void allowListIsNormalizedToo() {
        KioskBaseUrlPolicy policy = policyWith(" https://artifact-prod.duckdns.org/ , http://192.168.0.12:3000 ");
        assertThat(policy.sanitize("https://artifact-prod.duckdns.org"))
                .isEqualTo("https://artifact-prod.duckdns.org");
        assertThat(policy.sanitize("http://192.168.0.12:3000/"))
                .isEqualTo("http://192.168.0.12:3000");
    }

    @Test
    @DisplayName("대소문자가 달라도 같은 주소로 본다")
    void hostAndSchemeAreCaseInsensitive() {
        KioskBaseUrlPolicy policy = policyWith("https://artifact-prod.duckdns.org");
        assertThat(policy.sanitize("HTTPS://Artifact-Prod.DuckDNS.org"))
                .isEqualTo("https://artifact-prod.duckdns.org");
    }
}
