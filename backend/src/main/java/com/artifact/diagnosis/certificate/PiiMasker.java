package com.artifact.diagnosis.certificate;

import java.util.regex.Pattern;

/**
 * 외부 LLM에 보내기 전 개인 식별정보를 걷어낸다.
 *
 * 초안을 만드는 데 필요한 것은 "무슨 병으로 어떤 치료를 받았는가"이지 환자가 누구인지가 아니다.
 * 이름과 연락처는 프롬프트를 구성할 때 애초에 넣지 않으므로 여기서 다룰 필요가 없지만,
 * 접수 메모와 의사 메모는 사람이 자유롭게 쓰는 칸이라 "김○○ 님 010-…" 같은 문장이 섞여 들어온다.
 * 그 메모를 그대로 보내면 결국 환자 신원이 외부 서버 로그에 남는다.
 *
 * 정규식으로 걸러내는 방식이라 완벽하지 않다. 그래서 메모는 보조 정보로만 넘기고,
 * 초안의 사실 근거는 병명·약품처럼 구조화된 필드에서 가져온다.
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
