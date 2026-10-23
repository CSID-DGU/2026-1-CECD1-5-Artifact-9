package com.artifact.diagnosis.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AnalysisType} 은 JSON 에서 enum 이름이 아니라 소문자 코드로 오가야 한다 — 프론트·FastAPI·DB 가
 * 그 문자열("skin_lesion"/"facial")을 쓴다. Spring 컨텍스트 없이 ObjectMapper 만으로 확인한다.
 */
class AnalysisTypeJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAsLowerCaseCode() throws Exception {
        assertThat(objectMapper.writeValueAsString(AnalysisType.SKIN_LESION)).isEqualTo("\"skin_lesion\"");
        assertThat(objectMapper.writeValueAsString(AnalysisType.FACIAL)).isEqualTo("\"facial\"");
    }

    /** 필드를 생략한 옛 요청은 null 로 들어오고, 서비스가 기본 태스크로 접는다 — 기존 호출자는 바꿀 것이 없다. */
    @Test
    void deserializesFromCodeAndIsNullWhenAbsent() throws Exception {
        AnalysisRequest withType = objectMapper.readValue(
                "{\"imageIds\":[1],\"analysisType\":\"facial\"}", AnalysisRequest.class);
        assertThat(withType.analysisType()).isEqualTo(AnalysisType.FACIAL);

        AnalysisRequest legacy = objectMapper.readValue("{\"imageIds\":[1]}", AnalysisRequest.class);
        assertThat(legacy.analysisType()).isNull();
        assertThat(AnalysisType.orDefault(legacy.analysisType())).isEqualTo(AnalysisType.SKIN_LESION);
    }

    /**
     * 모르는 코드는 기본값으로 조용히 접지 않고 거부한다. enum 이름(SKIN_LESION)도 받지 않는다 —
     * 정식 표기를 하나로 두어야 세 층의 문자열이 어긋나지 않는다.
     */
    @Test
    void rejectsUnknownCodeInsteadOfSilentlyDefaulting() {
        for (String bad : new String[]{"burn", "SKIN_LESION", ""}) {
            assertThatThrownBy(() -> objectMapper.readValue(
                    "{\"imageIds\":[1],\"analysisType\":\"" + bad + "\"}", AnalysisRequest.class))
                    .as("analysisType=%s", bad)
                    .isInstanceOf(ValueInstantiationException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("알 수 없는 분석 종류");
        }
    }
}
