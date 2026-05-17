package com.artifact.diagnosis.visit;

/**
 * 내원(Visit) 진행 상태.
 *
 * 정상 흐름:
 *   RECEIVED → IN_PROGRESS → ANALYZING → ANALYZED
 *           → DIAGNOSED → PRESCRIBED → COMPLETED
 *
 * 예외 흐름:
 *   임의 시점에서 CANCELLED 가능.
 */
public enum VisitStatus {
    RECEIVED,        // 접수 완료, 대기 중
    IN_PROGRESS,     // 진료 시작, 이미지 업로드 및 AI 분석 가능
    ANALYZING,       // AI 분석 요청 후 응답 대기
    ANALYZED,        // AI 분석 완료, 의사 확정 전
    DIAGNOSED,       // 의사가 병명 확정, 처방 작성 중
    PRESCRIBED,      // 처방 저장 완료
    COMPLETED,       // 처방전 발급 완료(=진료 종료)
    CANCELLED        // 취소
}
