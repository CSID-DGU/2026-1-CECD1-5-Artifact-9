package com.artifact.diagnosis.certificate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 증명서 무효 처리 요청.
 *
 * 사유를 필수로 받는다. 발급대장에 "무효"라고만 남고 이유가 없으면
 * 나중에 그 서류가 왜 취소됐는지 아무도 설명할 수 없다.
 */
public record CertificateVoidRequest(
        @NotBlank(message = "무효 사유는 필수입니다.")
        @Size(max = 300, message = "무효 사유는 300자를 넘을 수 없습니다.")
        String reason
) {}
