package com.artifact.diagnosis.prescription;

import java.util.List;

public record PrescriptionCommentRequest(
        List<DiseaseInfo> diseases,
        String receptionMemo
) {
    public record DiseaseInfo(
            String kcdCode,
            String kcdNameKr,
            boolean isPrimary
    ) {}
}
