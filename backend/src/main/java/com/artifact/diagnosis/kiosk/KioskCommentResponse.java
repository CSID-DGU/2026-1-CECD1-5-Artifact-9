package com.artifact.diagnosis.kiosk;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 예비분석 참고 소견 응답.
 *
 * 소견은 {@link PreliminaryAnalysisResponse} 에도 들어 있지만 분석 직후에는 늘 null 이다 — 분석 응답에서 떼어
 * 화면이 결과를 그린 뒤 따로 받기 때문이다({@link KioskService#generateComment}). 필드가 하나뿐인데도 record 로
 * 감싸는 것은 문자열을 그대로 내보내면 JSON 이 아니라 text/plain 이 되어 프론트의 파싱 경로가 갈라지기 때문이다.
 */
@Schema(description = "키오스크 예비분석 AI 참고 소견 — 의학적 진단이 아님")
public record KioskCommentResponse(
        @Schema(description = "AI 참고 소견. 생성에 실패해도 null 이 아니라 안내 문구가 담긴다")
        String aiComment
) {}
