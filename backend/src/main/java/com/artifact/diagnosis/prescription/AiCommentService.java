package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.analysis.TopKItem;
import com.artifact.diagnosis.drug.DrugMaster;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import com.artifact.diagnosis.llm.LlmFailure;
import com.artifact.diagnosis.llm.LlmGateway;
import com.artifact.diagnosis.llm.LlmMessages;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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

    private final DrugMasterRepository drugMasterRepository;
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
        // 피부 치료 관련 약품 10개 조회
        List<DrugMaster> drugs = drugMasterRepository.findByNameKrContaining("연고", PageRequest.of(0, 5));
        drugs.addAll(drugMasterRepository.findByNameKrContaining("크림", PageRequest.of(0, 5)));

        // 약품 목록 문자열 생성
        String drugList = drugs.stream()
                .map(d -> d.getNameKr() + "(" + d.getCode() + ")")
                .distinct()
                .limit(10)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

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

                참고 약품 목록 (이 중에서 적합한 것을 선택하여 약품명과 코드를 포함):
                %s

                출력 규칙:
                - 정확히 2줄만 출력하세요 (번호, 레이블, 기호 없이)
                - 1줄: 추천 처방 약품명(코드) 포함하여 처방 방향 (예: ○○연고(코드)를 하루 2회 도포하는 것을 권장합니다.)
                - 2줄: 환자 주의사항 (자외선 차단, 재방문 시기 등)
                """,
                primaryDisease,
                secondaryDiseases.isEmpty() ? "없음" : secondaryDiseases,
                memo,
                drugList
        );

        LlmResult result = llmGateway.generate(LlmRequest.text("prescription-comment", prompt));
        if (!result.success()) {
            String message = result.isTemporaryFailure()
                    ? "AI 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해주세요."
                    : LlmMessages.reason(result);
            return PrescriptionCommentResponse.unavailable(message);
        }

        // \r\n, \r, \n 모두 처리
        String[] lines = result.text().split("\\r?\\n", 2);
        String line1 = lines[0].trim();
        String line2 = lines.length > 1 ? lines[1].trim() : "";
        return new PrescriptionCommentResponse(line1, line2, result.model(), true);
    }

    /**
     * 대기실 키오스크 예비분석용 참고 소견 생성.
     * 처방 코멘트(generateComment)와 달리 진단 확정이 아닌 "참고용" 톤을 강제하고,
     * 약품 추천 없이 AI 후보 목록만으로 짧은 안내 문구를 만든다.
     */
    public String generatePreliminaryComment(List<TopKItem> topK) {
        String candidateList = topK.stream()
                .map(item -> item.code() + " (" + String.format("%.1f", item.confidence() * 100) + "%)")
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

        String prompt = String.format("""
                당신은 피부과 대기실 키오스크의 AI 보조 안내 시스템입니다.
                아래는 환자가 대기 중 촬영한 사진에 대한 AI 모델의 후보 결과입니다.

                AI 후보 목록 (신뢰도 순): %s

                출력 규칙:
                - 정확히 2줄로, 진단을 단정하지 말고 참고 소견 톤으로 작성하세요.
                - 1줄: 후보 소견에 대한 부드러운 안내 (예: 촬영하신 부위는 ○○ 가능성이 있는 것으로 보입니다.)
                - 2줄: 반드시 의사의 확인 진료가 필요하다는 안내
                - 번호, 레이블, 기호 없이 순수 텍스트로만 출력하세요.
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
        return result.text();
    }
}
