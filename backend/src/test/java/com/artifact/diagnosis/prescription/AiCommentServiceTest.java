package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import com.artifact.diagnosis.llm.LlmGateway;
import com.artifact.diagnosis.llm.LlmResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCommentServiceTest {

    @Test
    @DisplayName("키오스크 소견은 20초 예산으로 LLM 을 부른다 — FastAPI 추론 뒤라 기본 30초를 쓰면 nginx 60초를 넘길 수 있다")
    void kioskCommentUsesKioskBudget() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.generate(any(), any(Duration.class))).thenReturn(LlmResult.success("소견 1줄\n소견 2줄", "gemini", "m"));

        String comment = new AiCommentService(mock(DrugMasterRepository.class), gateway)
                .generatePreliminaryComment(List.of(new TopKItem("acne", 0.8)));

        assertThat(comment).startsWith("소견 1줄");
        verify(gateway).generate(any(), eq(Duration.ofSeconds(20)));
    }
}
