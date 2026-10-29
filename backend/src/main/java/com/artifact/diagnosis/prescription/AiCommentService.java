package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.llm.LlmFailure;
import com.artifact.diagnosis.llm.LlmGateway;
import com.artifact.diagnosis.llm.LlmMessages;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 처방 코멘트(약품 추천 포함)와 키오스크 참고 소견을 LLM 으로 만든다.
 *
 * 예전 이름은 GeminiService 였다. 이제 {@link LlmGateway} 가 Gemini 1차 모델이 과부하일 때
 * 다른 Gemini 모델과 Claude 로 넘겨 주므로, 특정 공급자 이름을 달고 있을 이유가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCommentService {

    /**
     * 키오스크 소견의 LLM 시간 예산. 키오스크 요청은 FastAPI 추론(최대 fastapi.timeout-seconds, 기본 30초)을
     * 먼저 기다린 뒤 이 소견을 만든다. 둘을 합쳐도 앞단 nginx 의 60초 안에 끝나도록 기본 예산(30초)보다 짧게 둔다.
     * 넘기면 태블릿에는 오류가 뜨는데 백엔드는 결과를 저장해, 의사 화면과 어긋난다.
     * 기본 예산(llm.total-budget-seconds)을 이보다 줄이면 그쪽이 우선한다 — LlmGateway#generate 참고.
     */
    private static final Duration KIOSK_BUDGET = Duration.ofSeconds(20);

    /**
     * 줄 앞의 번호·글머리·"1줄:" 표시. 출력 규칙으로 막아도 가끔 붙어 나온다.
     * 번호 뒤에 공백을 요구해 "0.1% 연고를…" 같은 문장 첫머리의 숫자는 건드리지 않는다.
     */
    private static final Pattern LINE_MARKER = Pattern.compile("^(?:[-*•·]\\s+|\\d+[.)]\\s+|\\d+\\s*줄\\s*[:：]\\s*)");

    /** 한 줄에 두 문장을 이어 쓴 답을 첫 문장 끝에서 나눈다. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?。])\\s+");

    private final DrugCandidates drugCandidates;
    private final LlmGateway llmGateway;

    /**
     * 처방 코멘트 생성.
     *
     * @param maskedMemo 식별정보를 지운 접수 메모. 없으면 null.
     *                   <b>원문 메모를 그대로 넘기지 않는다</b> — 이 문자열은 그대로 외부(Google,
     *                   대체 경로에서는 Anthropic) 서버로 나가고 그쪽 로그에 남는다. 마스킹은 호출 전에 끝나 있어야 하며,
     *                   그 책임은 {@code PrescriptionService#maskedReceptionMemo} 에 있다.
     */
    public PrescriptionCommentResponse generateComment(PrescriptionCommentRequest req, String maskedMemo) {
        // 상병에 맞는 외용제만 후보로 준다. 주상병의 약이 먼저 들어가도록 주상병을 앞에 둔다.
        List<String> kcdCodes = Stream.concat(
                        req.diseases().stream().filter(PrescriptionCommentRequest.DiseaseInfo::isPrimary),
                        req.diseases().stream().filter(d -> !d.isPrimary()))
                .map(PrescriptionCommentRequest.DiseaseInfo::kcdCode)
                .toList();
        List<String> drugs = drugCandidates.forDiseases(kcdCodes);

        // 권할 약이 없을 때는 목록 이야기를 아예 꺼내지 않는다. "목록이 '없음'이면…" 같은 규칙을 주면
        // 모델이 "참고 약품 목록이 없어 약품명은 쓰지 않습니다"처럼 규칙을 코멘트에 되풀이한다(Haiku 5.5 실측).
        String drugSection = drugs.isEmpty() ? "" : """

                참고 약품 (상병에 맞춰 고른 외용제. 이 중에서 골라 약품명과 코드를 적힌 그대로 쓰세요):
                %s
                """.formatted(String.join(", ", drugs));
        String line1Rule = drugs.isEmpty()
                ? "- 1줄: 약품명 없이 처방 방향 (조직검사·절제·경과 관찰·전문 진료 의뢰·생활 관리 등)"
                : """
                - 1줄: 참고 약품 중 하나를 골라 약품명(코드)과 용법을 넣은 처방 방향 (예: ○○겔(코드)을 하루 1회 저녁에 얇게 도포하는 것을 권장합니다.)
                - 참고 약품에 없는 약품명이나 코드는 쓰지 마세요.""";

        // 주상병/부상병 정리
        String primaryDisease = req.diseases().stream()
                .filter(PrescriptionCommentRequest.DiseaseInfo::isPrimary)
                .map(d -> d.kcdNameKr() + "(" + d.kcdCode() + ")")
                .findFirst().orElse("");
        String secondaryDiseases = req.diseases().stream()
                .filter(d -> !d.isPrimary())
                .map(d -> d.kcdNameKr() + "(" + d.kcdCode() + ")")
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

        String memo = (maskedMemo != null && !maskedMemo.isBlank())
                ? "\n- 접수 메모: " + maskedMemo : "";

        String prompt = String.format("""
                당신은 피부과 진료 보조 시스템입니다. 아래 진단 정보를 바탕으로 처방 방향을 정확히 2줄로 작성하세요.

                진단 정보:
                - 주상병: %s
                - 부상병: %s%s
                %s
                출력 규칙:
                - 정확히 2줄만 출력하세요. 번호·레이블·기호·마크다운 없이, 빈 줄 없이 씁니다. 각 줄은 한 문장입니다.
                %s
                - 주상병이 악성 종양·모반처럼 약보다 조직검사·절제·경과 관찰이 먼저인 병변이면 1줄에 그 방향을 쓰세요.
                - 2줄: 환자 주의사항 (자외선 차단, 재방문 시기 등)
                - 모든 단어를 한글로 쓰세요(예: 환부, 도포). 한자는 쓰지 마세요.
                """,
                primaryDisease,
                secondaryDiseases.isEmpty() ? "없음" : secondaryDiseases,
                memo,
                drugSection,
                line1Rule
        );

        LlmResult result = llmGateway.generate(LlmRequest.text("prescription-comment", prompt));
        if (!result.success()) {
            String message = result.isTemporaryFailure()
                    ? "AI 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해주세요."
                    : LlmMessages.reason(result);
            return PrescriptionCommentResponse.unavailable(message);
        }

        List<String> lines = twoLines(result.text());
        return new PrescriptionCommentResponse(lines.get(0), lines.get(1), result.model(), true);
    }

    /**
     * 대기실 키오스크 예비분석용 참고 소견 생성.
     * 처방 코멘트(generateComment)와 달리 진단 확정이 아닌 "참고용" 톤을 강제하고,
     * 약품 추천 없이 AI 후보 목록만으로 짧은 안내 문구를 만든다.
     *
     * @param namesKo 모델 클래스 코드 → 한글 병명. 코드(nv, mel)를 그대로 주면 환자 화면에 영문 약어가 섞여 나온다.
     */
    public String generatePreliminaryComment(List<TopKItem> topK, Map<String, String> namesKo) {
        String candidateList = topK.stream()
                .map(item -> namesKo.getOrDefault(item.code(), item.code())
                        + " " + String.format("%.1f", item.confidence() * 100) + "%")
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

        String prompt = String.format("""
                당신은 피부과 대기실 키오스크의 AI 보조 안내 시스템입니다.
                아래는 환자가 대기 중 촬영한 사진에 대한 AI 모델의 후보 결과입니다.

                AI 후보 목록 (신뢰도 순): %s

                출력 규칙:
                - 정확히 2줄로, 진단을 단정하지 말고 참고 소견 톤으로 작성하세요.
                - 병명은 목록의 한글 이름을 그대로 쓰고, 영문 약어나 코드는 쓰지 마세요.
                - 1줄: 후보 소견에 대한 부드러운 안내 (예: 촬영하신 부위는 ○○ 가능성이 있는 것으로 보입니다.) 1순위가 '정상 피부'면 뚜렷한 이상 소견은 보이지 않는다고 안내하세요.
                - 2줄: 반드시 의사의 확인 진료가 필요하다는 안내
                - 번호, 레이블, 기호 없이 순수 텍스트로만 출력하세요.
                - 한글로 쓰고 한자는 쓰지 마세요.
                """, candidateList);

        LlmResult result = llmGateway.generate(LlmRequest.text("kiosk-comment", prompt), KIOSK_BUDGET);
        if (!result.success()) {
            if (result.failure() == LlmFailure.NOT_CONFIGURED) {
                return "AI 참고 소견을 생성할 수 없습니다 (API 키 미설정).";
            }
            return result.isTemporaryFailure()
                    ? "AI 서버가 일시적으로 혼잡합니다. 의사의 확인 진료가 필요합니다."
                    : "AI 참고 소견 생성에 실패했습니다. 의사의 확인 진료가 필요합니다.";
        }
        return String.join("\n", twoLines(result.text())).trim();
    }

    /**
     * 답을 화면이 기대하는 두 줄로 맞춘다. 공급자마다 버릇이 달라 같은 규칙을 줘도 모양이 조금씩 다르다 —
     * 빈 줄을 끼우거나, 번호·글머리를 붙이거나, 두 문장을 한 줄에 잇는다(Claude Haiku 5.5 실측).
     * 화면과 처방 기록은 1줄(처방 방향)·2줄(주의사항)을 따로 쓰므로 여기서 한 번 정리한다.
     * 셋째 줄부터는 버리지 않고 2줄 뒤에 붙인다.
     */
    static List<String> twoLines(String text) {
        List<String> lines = new ArrayList<>(text.lines()
                .map(line -> LINE_MARKER.matcher(line.replace("**", "").trim()).replaceFirst("").trim())
                .filter(line -> !line.isEmpty())
                .toList());
        if (lines.size() == 1) {
            String[] sentences = SENTENCE_END.split(lines.get(0), 2);
            if (sentences.length == 2) return List.of(sentences[0].trim(), sentences[1].trim());
        }
        if (lines.isEmpty()) return List.of("", "");
        String rest = String.join(" ", lines.subList(1, lines.size()));
        return List.of(lines.get(0), rest);
    }
}
