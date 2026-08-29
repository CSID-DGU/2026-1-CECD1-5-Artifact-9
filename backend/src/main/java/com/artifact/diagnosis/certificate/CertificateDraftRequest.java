package com.artifact.diagnosis.certificate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * LLM 초안 생성 요청.
 *
 * 용도와 제출처를 함께 받는 이유는 같은 진료라도 어디에 내느냐에 따라 문장이 달라지기 때문이다.
 * 보험사에 낼 진단서는 치료 경과와 예상 기간이 중요하고, 회사에 낼 것은 근무 가능 여부가 중요하다.
 */
public record CertificateDraftRequest(

        @NotNull(message = "서류 종류는 필수입니다.")
        CertificateType type,

        @Size(max = 200, message = "용도는 200자를 넘을 수 없습니다.")
        String purpose,

        @Size(max = 200, message = "제출처는 200자를 넘을 수 없습니다.")
        String submitTo,

        /** 진료의뢰서를 받을 의료기관. 의뢰 사유 문장에 반영된다. */
        @Size(max = 200, message = "의뢰 의료기관은 200자를 넘을 수 없습니다.")
        String referralTo
) {}
