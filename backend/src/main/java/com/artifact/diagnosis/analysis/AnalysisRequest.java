package com.artifact.diagnosis.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "AI 분석 요청")
public record AnalysisRequest(
        @NotEmpty
        @Schema(description = "분석할 이미지 ID 목록 (visit_image.image_id)", example = "[1, 2]")
        List<Long> imageIds,

        /**
         * 어느 모델로 분석할지. 생략(null)하면 skin_lesion — 이 필드가 생기기 전의 호출자는 요청을
         * 바꾸지 않아도 전과 똑같이 동작한다. 허용 값 밖의 문자열은 역직렬화에서 400 ({@link AnalysisType#fromCode}).
         */
        @Schema(description = "분석 종류(모델) 코드. 생략 시 skin_lesion. skin_lesion=피부 병변 8종 / facial=안면부 피부질환 6종",
                example = "skin_lesion", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        AnalysisType analysisType
) {}
