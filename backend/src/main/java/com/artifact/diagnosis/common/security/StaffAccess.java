package com.artifact.diagnosis.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인한 모든 직책(접수·간호·의사·관리자)이 호출할 수 있다.
 *
 * 접수 업무와 조회 화면이 여기 해당한다 — 환자 등록·검색, 접수 생성, 접수 목록,
 * 지난 진료 기록·처방·분석 결과 열람, KCD/약품 마스터 검색.
 *
 * "아무나 열 수 있다"는 뜻이 아니다. 로그인은 반드시 필요하고, 누가 무엇을 열었는지는
 * 감사 로그(G3)에서 남긴다. 의료법이 요구하는 통제는 "의사만 차트를 본다"가 아니라
 * 역할 기반 접근 + 접속기록이다 — 계획서 §2 (C) 참고.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@PreAuthorize("hasAnyRole('STAFF','NURSE','DOCTOR','ADMIN')")
public @interface StaffAccess {
}
