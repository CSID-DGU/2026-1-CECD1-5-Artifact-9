package com.artifact.diagnosis.llm;

import java.util.List;

/**
 * 공급자(Gemini, Claude)와 무관한 생성 요청 한 건.
 *
 * 응답 형식은 두 가지뿐이다. 자유 텍스트(처방 코멘트, 키오스크 소견)와, 문자열 필드 몇 개로
 * 이루어진 JSON(증명서 서술 칸)이다. 그래서 스키마를 통째로 받지 않고 필드 이름 목록만 받는다.
 * 공급자마다 스키마 문법이 다르기 때문이다(Gemini 는 "OBJECT"/"STRING", Claude 는 JSON Schema).
 * 각 공급자가 이 목록을 자기 문법으로 바꾼다.
 *
 * @param purpose     로그에만 쓰는 이름(예: "prescription-comment"). 프롬프트에는 들어가지 않는다.
 * @param prompt      그대로 외부로 나가는 문자열이다. 개인정보 마스킹은 호출 전에 끝나 있어야 한다.
 * @param jsonFields  JSON 응답이 필요할 때 받을 문자열 필드 이름들. 자유 텍스트면 null.
 * @param temperature Gemini 에만 전달한다. Claude 최신 모델은 sampling 파라미터를 받으면 400 이다.
 */
public record LlmRequest(String purpose, String prompt, List<String> jsonFields, Double temperature) {

    public static LlmRequest text(String purpose, String prompt) {
        return new LlmRequest(purpose, prompt, null, null);
    }

    public static LlmRequest json(String purpose, String prompt, List<String> jsonFields, Double temperature) {
        return new LlmRequest(purpose, prompt, List.copyOf(jsonFields), temperature);
    }

    public boolean wantsJson() {
        return jsonFields != null && !jsonFields.isEmpty();
    }
}
