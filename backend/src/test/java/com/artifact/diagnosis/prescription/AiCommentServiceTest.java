package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.llm.LlmGateway;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCommentServiceTest {

    private static final Map<String, String> NAMES = Map.of(
            "nv", "멜라닌세포모반", "mel", "악성 흑색종", "inflammatory", "염증성 피부질환", "acne", "여드름");

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final DrugCandidates drugCandidates = mock(DrugCandidates.class);
    private final AiCommentService service = new AiCommentService(drugCandidates, gateway);

    @Test
    @DisplayName("키오스크 소견은 20초 예산으로 LLM 을 부른다 — FastAPI 추론 뒤라 기본 30초를 쓰면 nginx 60초를 넘길 수 있다")
    void kioskCommentUsesKioskBudget() {
        when(gateway.generate(any(), any(Duration.class))).thenReturn(LlmResult.success("소견 1줄\n소견 2줄", "gemini", "m"));

        String comment = service.generatePreliminaryComment(List.of(new TopKItem("acne", 0.8)), NAMES);

        assertThat(comment).isEqualTo("소견 1줄\n소견 2줄");
        verify(gateway).generate(any(), eq(Duration.ofSeconds(20)));
    }

    @Test
    @DisplayName("키오스크 소견도 번호·빈 줄을 걷어 두 줄로 저장한다 — 태블릿과 의사 화면에 그대로 나간다")
    void kioskCommentIsNormalizedToTwoLines() {
        when(gateway.generate(any(), any(Duration.class))).thenReturn(
                LlmResult.success("1. 여드름 가능성이 있어 보입니다.\n\n2. 의사의 확인 진료가 필요합니다.", "claude", "m"));

        String comment = service.generatePreliminaryComment(List.of(new TopKItem("acne", 0.8)), NAMES);

        assertThat(comment).isEqualTo("여드름 가능성이 있어 보입니다.\n의사의 확인 진료가 필요합니다.");
    }

    @Test
    @DisplayName("키오스크 프롬프트에는 모델 코드 대신 한글 병명이 간다 — 코드를 주면 환자 화면에 nv·mel 같은 약어가 섞인다")
    void kioskPromptUsesKoreanNames() {
        when(gateway.generate(any(), any(Duration.class))).thenReturn(LlmResult.success("가\n나", "claude", "m"));

        service.generatePreliminaryComment(List.of(new TopKItem("nv", 0.83), new TopKItem("mel", 0.05),
                new TopKItem("unknown_code", 0.01)), NAMES);

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).generate(sent.capture(), any(Duration.class));
        String prompt = sent.getValue().prompt();
        assertThat(prompt).contains("멜라닌세포모반 83.0%, 악성 흑색종 5.0%")
                .doesNotContain("nv (").doesNotContain("mel (");
        // 표에 없는 코드는 그대로 둔다 — 빠뜨리는 것보다 낫다.
        assertThat(prompt).contains("unknown_code 1.0%");
    }

    @Test
    @DisplayName("처방 코멘트 프롬프트의 약품 목록은 주상병부터 상병에 맞춰 고른 후보다")
    void commentPromptListsCandidatesForTheDiseases() {
        when(drugCandidates.forDiseases(List.of("L700", "L219")))
                .thenReturn(List.of("디투오겔(아다팔렌)(057000841)", "케로졸크림(케토코나졸)(057001321)"));
        when(gateway.generate(any())).thenReturn(LlmResult.success("가\n나", "claude", "claude-haiku-5-5"));

        // 부상병을 먼저 보내도 주상병의 약이 앞에 온다.
        service.generateComment(new PrescriptionCommentRequest(List.of(
                new PrescriptionCommentRequest.DiseaseInfo("L219", "상세불명의 지루피부염", false),
                new PrescriptionCommentRequest.DiseaseInfo("L700", "보통여드름", true))), null);

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).generate(sent.capture());
        assertThat(sent.getValue().prompt())
                .contains("참고 약품 (상병에 맞춰 고른 외용제. 이 중에서 골라 약품명과 코드를 적힌 그대로 쓰세요):\n"
                        + "디투오겔(아다팔렌)(057000841), 케로졸크림(케토코나졸)(057001321)")
                .contains("- 1줄: 참고 약품 중 하나를 골라 약품명(코드)과 용법을 넣은 처방 방향")
                .contains("주상병: 보통여드름(L700)");
    }

    @Test
    @DisplayName("맞는 약이 없으면 약품 목록을 빼고, 약 대신 처방 방향을 쓰라고 이른다")
    void commentPromptWithoutCandidates() {
        when(drugCandidates.forDiseases(any())).thenReturn(List.of());
        when(gateway.generate(any())).thenReturn(LlmResult.success("가\n나", "gemini", "m"));

        service.generateComment(new PrescriptionCommentRequest(List.of(
                new PrescriptionCommentRequest.DiseaseInfo("C439", "상세불명의 피부의 악성 흑색종", true))), null);

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).generate(sent.capture());
        // 목록 이야기를 아예 꺼내지 않는다 — 규칙을 주면 모델이 그 규칙을 코멘트에 되풀이한다.
        assertThat(sent.getValue().prompt()).contains("- 1줄: 약품명 없이 처방 방향")
                .doesNotContain("참고 약품");
    }

    @Test
    @DisplayName("답의 빈 줄·번호·굵은 글씨를 걷어 1줄·2줄로 맞춘다")
    void normalizesDecoratedAnswers() {
        assertThat(AiCommentService.twoLines("1. **가 연고(A1)를 바릅니다.**\n\n2) 자외선을 피하세요."))
                .containsExactly("가 연고(A1)를 바릅니다.", "자외선을 피하세요.");
        assertThat(AiCommentService.twoLines("- 가를 바릅니다.\n- 나를 피하세요."))
                .containsExactly("가를 바릅니다.", "나를 피하세요.");
        assertThat(AiCommentService.twoLines("1줄: 가를 바릅니다.\n2줄: 나를 피하세요."))
                .containsExactly("가를 바릅니다.", "나를 피하세요.");
    }

    @Test
    @DisplayName("한 줄에 두 문장을 이어 쓰면 첫 문장 끝에서 나누고, 셋째 줄부터는 2줄 뒤에 붙인다")
    void splitsOrJoinsToTwoLines() {
        assertThat(AiCommentService.twoLines("가를 하루 2회 바르는 것을 권장합니다. 2주 뒤 재방문하세요."))
                .containsExactly("가를 하루 2회 바르는 것을 권장합니다.", "2주 뒤 재방문하세요.");
        assertThat(AiCommentService.twoLines("가를 바릅니다.\n나를 피하세요.\n2주 뒤 오세요."))
                .containsExactly("가를 바릅니다.", "나를 피하세요. 2주 뒤 오세요.");
        // 문장 첫머리의 숫자는 번호가 아니다.
        assertThat(AiCommentService.twoLines("0.1% 연고를 바릅니다.\n자외선을 피하세요."))
                .containsExactly("0.1% 연고를 바릅니다.", "자외선을 피하세요.");
        assertThat(AiCommentService.twoLines("한 문장뿐입니다")).containsExactly("한 문장뿐입니다", "");
    }

    @Test
    @DisplayName("정리한 두 줄이 처방 코멘트 응답의 line1·line2 로 간다")
    void commentResponseUsesNormalizedLines() {
        when(drugCandidates.forDiseases(any())).thenReturn(List.of());
        when(gateway.generate(any())).thenReturn(
                LlmResult.success("가 연고(A1)를 바릅니다.\n\n자외선을 피하세요.", "claude", "claude-haiku-5-5"));

        PrescriptionCommentResponse response = service.generateComment(new PrescriptionCommentRequest(List.of(
                new PrescriptionCommentRequest.DiseaseInfo("L700", "보통여드름", true))), null);

        assertThat(response.line1()).isEqualTo("가 연고(A1)를 바릅니다.");
        assertThat(response.line2()).isEqualTo("자외선을 피하세요.");
        assertThat(response.model()).isEqualTo("claude-haiku-5-5");
    }
}
