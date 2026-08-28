package com.artifact.diagnosis.certificate;

/**
 * 발급된 증명서의 상태.
 *
 * <p>잘못 발급한 서류라도 행을 지우지 않는다. 발급대장에서 기록이 사라지면
 * "그날 그 서류가 나갔는지" 자체를 확인할 수 없게 되기 때문이다.
 * 대신 {@link #VOID} 로 무효 표시하고 사유를 남긴다.
 */
public enum CertificateStatus {
    /** 정상 발급 — 재출력 가능. */
    ISSUED,
    /** 무효 처리됨 — 이력에는 남지만 재출력은 막는다. */
    VOID
}
