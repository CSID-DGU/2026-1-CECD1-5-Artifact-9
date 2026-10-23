package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * AI 분석 태스크 — 어느 모델(가중치·클래스 목록)로 추론할지.
 *
 * FastAPI 는 모델을 태스크별로 든다(fastapi/main.py 의 {@code TASKS}). 이 enum 의 {@link #code()} 가
 * 그쪽 딕셔너리 키이자 요청 본문의 {@code "task"} 값이고, {@code analysis_result.analysis_type} 에
 * 저장되는 문자열이기도 하다. 계약 원본은 docs/aihub-datasets-plan.md §3-1.
 *
 * JSON 으로는 enum 이름(SKIN_LESION)이 아니라 소문자 코드("skin_lesion")로 오간다. 프론트·FastAPI·DB
 * 세 곳이 이미 같은 소문자 문자열을 쓰는데 Spring 경계에서만 대문자로 바꾸면 변환이 한 겹 더 생기고,
 * 그 한 겹이 어긋났을 때의 증상이 "서버는 뜨고 병명만 조용히 틀리는" 바로 그것이다.
 *
 * 요청에 없으면(null) {@link #SKIN_LESION} — 이 필드가 생기기 전의 호출자는 아무것도 바꾸지 않아도
 * 전과 같은 동작이다.
 */
public enum AnalysisType {

    /** 기존 피부 병변 8클래스(HAM10000 7종 + SCIN 염증성 1종). fastapi/model.pth. 기본값. */
    SKIN_LESION("skin_lesion"),

    /** 안면부 피부질환 6클래스(AI허브 71863 합성 데이터). fastapi/models/facial.pth. */
    FACIAL("facial");

    /** 요청에 analysisType 이 없을 때 쓰는 태스크. 기존 서비스와 100% 같은 동작이 보장되는 쪽이다. */
    public static final AnalysisType DEFAULT = SKIN_LESION;

    private final String code;

    AnalysisType(String code) {
        this.code = code;
    }

    /** FastAPI 요청의 {@code task} 값이자 DB 에 저장되는 문자열. JSON 직렬화에도 이 값이 나간다. */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 소문자 코드 → enum. 모르는 값이면 {@link IllegalArgumentException} — Jackson 이 이를
     * {@code HttpMessageNotReadableException} 으로 감싸고 {@code GlobalExceptionHandler} 가 400 으로 돌려준다.
     * 조용히 기본값으로 접지 않는다: 오타 난 태스크로 엉뚱한 모델이 도는 쪽이 400 보다 훨씬 나쁘다.
     */
    @JsonCreator
    public static AnalysisType fromCode(String code) {
        for (AnalysisType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("알 수 없는 분석 종류: " + code + " (허용: "
                + Arrays.stream(values()).map(AnalysisType::code).collect(Collectors.joining(", ")) + ")");
    }

    /** null 이면 기본 태스크. 컨트롤러·서비스 어느 경로로 들어와도 같은 규칙이 적용되도록 한 곳에 둔다. */
    public static AnalysisType orDefault(AnalysisType type) {
        return type != null ? type : DEFAULT;
    }
}
