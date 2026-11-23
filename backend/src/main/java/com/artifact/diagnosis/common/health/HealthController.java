package com.artifact.diagnosis.common.health;

import com.artifact.diagnosis.common.security.PublicEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 로드밸런서(ALB) 헬스체크.
 *
 * ALB 대상 그룹은 몇 초마다 이 주소를 부르고, 200 이 아니면 그 인스턴스로 요청을 보내지 않는다.
 * 로그인 없이 불려야 하므로 공개다(SecurityConfig permitAll). 돌려주는 것은 상태 문자열 하나뿐이고
 * 환자 데이터·설정값·버전에는 닿지 않는다.
 *
 * DB 는 일부러 보지 않는다. 이 주소는 "이 프로세스가 요청을 받을 수 있는가"만 답한다. DB 가 잠깐 느려질 때
 * 여기까지 실패하면 ALB 가 백엔드를 통째로 빼 버려, 몇 초짜리 지연이 전체 503 으로 커진다. DB 까지 닿는지는
 * 배포 워크플로가 로그인 요청으로 따로 확인한다(.github/workflows/deploy.yml 헬스체크).
 */
@RestController
@PublicEndpoint // 로드밸런서가 인증 없이 부른다. 상태 문자열 외에는 아무것도 내보내지 않는다.
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
