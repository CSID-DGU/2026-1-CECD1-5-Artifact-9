package com.artifact.diagnosis.prescription;

/**
 * AI 처방 코멘트.
 *
 * @param line1     처방 방향(추천 약품 포함). 실패하면 사용자에게 보일 사유 문구가 들어간다.
 * @param line2     환자 주의사항. 실패하면 빈 문자열.
 * @param model     실제로 응답한 모델 ID. 1차 모델이 과부하면 대체 모델이 답하므로 화면이 모델명을
 *                  고정해 두면 안 된다 — 처방 기록(ai_comment_model)에 이 값을 그대로 남긴다. 실패면 null.
 * @param available 코멘트가 실제로 만들어졌는지. false 면 line1 은 코멘트가 아니라 실패 사유다.
 *                  화면이 이것을 보고 사유를 코멘트 칸이 아닌 경고로 띄운다 — 사유가 코멘트로
 *                  저장되는 일을 막기 위해서다.
 */
public record PrescriptionCommentResponse(
        String line1,
        String line2,
        String model,
        boolean available
) {

    public static PrescriptionCommentResponse unavailable(String reason) {
        return new PrescriptionCommentResponse(reason, "", null, false);
    }
}
