package com.artifact.diagnosis.common.util;

/**
 * 화면·종이에 찍히는 표시용 번호.
 *
 * 환자번호와 접수번호는 별도 컬럼이 아니라 PK 를 자리수만 맞춰 찍은 값이다.
 * 같은 규칙이 접수 화면(Reception.tsx), 감열지 출력(PrintService), 공개 열람
 * 페이지(DocumentShareService) 세 곳에서 필요한데, 각자 포맷 문자열을 들고 있으면
 * 한 곳만 고쳤을 때 같은 환자의 번호가 종이와 화면에서 달라진다. 그래서 여기 모은다.
 */
public final class DisplayNo {

    private DisplayNo() {}

    public static String patient(Long patientId) {
        return "P" + String.format("%05d", patientId);
    }

    public static String visit(Long visitId) {
        return "V" + String.format("%05d", visitId);
    }
}
