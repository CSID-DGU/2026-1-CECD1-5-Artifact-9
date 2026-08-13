package com.artifact.diagnosis.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>간호사 이상</b>(간호·의사·관리자)만 호출할 수 있다. 접수 직원은 막힌다.
 *
 * <p>진료 화면에서 실제 의료 행위에 해당하는 조작 — 병변 이미지 업로드, AI 분석 요청, 진료 시작.
 * 접수 직원이 환자 차트에 이미지를 붙이거나 AI 분석을 돌릴 이유가 없다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@PreAuthorize("hasAnyRole('NURSE','DOCTOR','ADMIN')")
public @interface MedicalAccess {
}
