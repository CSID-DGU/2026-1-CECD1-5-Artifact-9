package com.artifact.diagnosis.common.util;

import java.util.regex.Pattern;

/**
 * 외부 LLM에 보내기 전 개인 식별정보를 걷어낸다.
 *
 * 모델에게 필요한 것은 "무슨 병으로 어떤 치료를 받았는가"이지 환자가 누구인지가 아니다.
 * 이름과 연락처는 프롬프트를 구성할 때 애초에 넣지 않지만, 접수 메모와 의사 메모는 사람이
 * 자유롭게 쓰는 칸이라 "김○○ 님 010-…" 같은 문장이 섞여 들어온다.
 * 그 메모를 그대로 보내면 결국 환자 신원이 외부 서버 로그에 남는다.
 *
 * 정규식으로 걸러내는 방식이라 완벽하지 않다. 그래서 메모는 보조 정보로만 넘기고,
 * 사실 근거는 병명·약품처럼 구조화된 필드에서 가져온다.
 *
 * <p>이 클래스가 {@code certificate} 패키지가 아니라 공용 유틸에 있는 이유가 있다.
 * 원래는 증명서 초안 경로에만 있었고, 나중에 생긴 처방 코멘트 경로(
 * {@code PrescriptionController#comment})는 같은 접수 메모를 마스킹 없이 그대로
 * Gemini 로 보내고 있었다. 한 도메인 안에 숨어 있으면 다음에 LLM 을 부르는 사람이
 * 이런 것이 있는 줄도 모른다. <b>외부 모델에 자유 서술 텍스트를 보내는 경로는
 * 예외 없이 이 함수를 지나야 한다.</b>
 */
public final class PiiMasker {

    /** 010-1234-5678, 01012345678, 02-123-4567 등. */
    private static final Pattern PHONE = Pattern.compile("\\b0\\d{1,2}[- ]?\\d{3,4}[- ]?\\d{4}\\b");

    /** 900101-1234567 형태의 주민등록번호. */
    private static final Pattern RRN = Pattern.compile("\\b\\d{6}[- ]?[1-4]\\d{6}\\b");

    /** 이메일. */
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    private PiiMasker() {}

    /**
     * 메모에서 식별정보를 지운다.
     *
     * @param patientName 지울 환자 이름. 메모에 이름이 적혀 있으면 '환자'로 바꾼다.
     */
    public static String mask(String text, String patientName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String masked = text;
        if (patientName != null && patientName.length() >= 2) {
            masked = masked.replace(patientName, "환자");
        }
        masked = RRN.matcher(masked).replaceAll("[주민번호]");
        masked = PHONE.matcher(masked).replaceAll("[연락처]");
        masked = EMAIL.matcher(masked).replaceAll("[이메일]");
        return masked;
    }
}
