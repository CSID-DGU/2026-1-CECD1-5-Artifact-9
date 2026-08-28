package com.artifact.diagnosis.certificate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 증명서 발급 요청.
 *
 * <p>병명·약품명·날짜·면허번호는 <b>여기 없다</b>. 전부 서버가 DB에서 채운다.
 * 요청으로 받으면 화면을 조작해 실제 진료기록과 다른 서류를 만들 수 있고,
 * 그렇게 나간 종이는 보험금 청구와 병가 증빙에 그대로 쓰인다.
 * 클라이언트가 정할 수 있는 것은 용도·제출처와 의사가 직접 쓴 서술 내용뿐이다.
 */
public record CertificateIssueRequest(

        @NotNull(message = "서류 종류는 필수입니다.")
        CertificateType type,

        @Size(max = 200, message = "용도는 200자를 넘을 수 없습니다.")
        String purpose,

        @Size(max = 200, message = "제출처는 200자를 넘을 수 없습니다.")
        String submitTo,

        /** 진단서의 '향후 치료에 대한 소견', 소견서 본문, 의뢰서의 임상 소견. */
        String opinion,

        /** 진단서의 향후 치료계획. */
        String treatmentPlan,

        /** 진료의뢰서의 의뢰 사유. */
        String referralReason,

        String remarks,

        /** 진료의뢰서를 받을 의료기관. */
        @Size(max = 200, message = "의뢰 의료기관은 200자를 넘을 수 없습니다.")
        String referralTo,

        /** 처방전 사용기간(일). 미지정 시 3일. */
        Integer prescriptionValidDays,

        /** LLM 초안 원문. 의사가 고쳤더라도 무엇이 자동 생성이었는지 남기기 위해 함께 보낸다. */
        String aiDraft,

        String aiModel,

        /** 의사가 초안을 수정했으면 true. */
        Boolean aiEdited
) {}
