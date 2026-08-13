package com.artifact.diagnosis.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>의사만</b>(+관리자) 호출할 수 있다.
 *
 * <p>법적으로 의사의 판단이 필요한 행위 — 처방 저장, 진단 확정, 진료 완료, AI 처방 코멘트 생성.
 * 이미 이슈 #4에서 "처방의 = 토큰의 본인"을 서버가 강제하고 있고, 이 애노테이션은 그 앞단에서
 * <b>애초에 의사가 아닌 계정은 진입조차 못 하게</b> 한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
public @interface DoctorAccess {
}
