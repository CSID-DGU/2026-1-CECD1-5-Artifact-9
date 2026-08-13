package com.artifact.diagnosis.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증 없이 열려 있음을 <b>의도적으로</b> 선언하는 표식. 권한을 부여하지 않는다 — 아무 동작도 없다.
 *
 * <p>존재 이유는 하나다. {@code EndpointAuthorizationGuardTest}가 "인가 애노테이션이 없는 핸들러"를
 * 전부 빌드 실패로 잡는데, 정말 공개여야 하는 경로(로그인/가입, 키오스크 태블릿)까지 걸리기 때문이다.
 * 테스트에 경로 문자열 목록을 하드코딩해 두면 코드와 목록이 조용히 어긋난다.
 * 대신 <b>공개하겠다는 결정을 코드에 남기게</b> 해서, 실수로 빠뜨린 것과 일부러 연 것을 구분한다.
 *
 * <p>여기에 이 애노테이션을 새로 다는 것은 "이 엔드포인트는 인터넷의 누구나 호출할 수 있다"는 선언이다.
 * 반드시 {@code SecurityConfig}의 permitAll 목록에도 같은 경로가 있어야 하고,
 * 리뷰에서 근거를 남겨야 한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {
}
