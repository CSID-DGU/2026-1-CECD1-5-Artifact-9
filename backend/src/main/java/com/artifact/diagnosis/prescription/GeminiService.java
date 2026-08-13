package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.drug.DrugMaster;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final DrugMasterRepository drugMasterRepository;
    private final ObjectMapper objectMapper;

    /** 요청마다 새로 만들지 않고 공유한다 — {@code HttpClientConfig} 참고. 연결 타임아웃이 걸려 있다. */
    private final HttpClient httpClient;

    @Value("${gemini.api.key:}")
    private String apiKey;

    /**
     * Gemini 응답 대기 한도.
     *
     * <p>키오스크 예비분석은 이 호출이 끝나야 태블릿에 결과를 띄운다. 무한 대기면 환자가
     * 로딩 화면만 보게 되므로, 짧게 끊고 아래 fallback 문구로 넘어가는 편이 낫다 —
     * AI 코멘트는 없어도 되는 부가 정보지 진단 결과가 아니다.
     */
    @Value("${gemini.timeout-seconds:15}")
    private long timeoutSeconds;

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=";

    private static final int MAX_RETRY = 3;

    public PrescriptionCommentResponse generateComment(PrescriptionCommentRequest req) {
        if (apiKey == null || apiKey.isBlank()) {
            return new PrescriptionCommentResponse("Gemini API 키가 설정되지 않았습니다.", "");
        }

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

        String memo = (req.receptionMemo() != null && !req.receptionMemo().isBlank())
                ? "\n- 접수 메모: " + req.receptionMemo() : "";

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

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = null;

            // 503(서버 과부하) 한정으로 최대 MAX_RETRY회 재시도
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                log.debug("Gemini 응답 (attempt={}, status={}): {}", attempt, response.statusCode(), response.body());

                if (response.statusCode() != 503) break;

                if (attempt < MAX_RETRY) {
                    log.warn("Gemini 503 과부하, {}초 후 재시도 ({}/{})", attempt, attempt, MAX_RETRY);
                    Thread.sleep(attempt * 1000L); // 1초, 2초 간격
                }
            }

            JsonNode root = objectMapper.readTree(response.body());

            // API 에러 응답 처리
            if (root.has("error")) {
                int code = root.at("/error/code").asInt(0);
                String errorMsg = root.at("/error/message").asText("알 수 없는 오류");
                log.error("Gemini API 에러: {}", errorMsg);
                String userMsg = (code == 503)
                        ? "AI 서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도해주세요."
                        : "API 오류: " + errorMsg;
                return new PrescriptionCommentResponse(userMsg, "");
            }

            String text = root.at("/candidates/0/content/parts/0/text").asText("").trim();

            if (text.isEmpty()) {
                log.warn("Gemini 응답 텍스트가 비어있음. 전체 응답: {}", response.body());
                return new PrescriptionCommentResponse("AI가 빈 응답을 반환했습니다.", "잠시 후 다시 시도해주세요.");
            }

            // \r\n, \r, \n 모두 처리
            String[] lines = text.split("\\r?\\n", 2);
            String line1 = lines[0].trim();
            String line2 = lines.length > 1 ? lines[1].trim() : "";

            return new PrescriptionCommentResponse(line1, line2);
        } catch (Exception e) {
            log.error("Gemini API 호출 실패: {}", e.getMessage());
            return new PrescriptionCommentResponse("AI 코멘트 생성에 실패했습니다.", "잠시 후 다시 시도해주세요.");
        }
    }

    /**
     * 대기실 키오스크 예비분석용 참고 소견 생성.
     * 처방 코멘트(generateComment)와 달리 진단 확정이 아닌 "참고용" 톤을 강제하고,
     * 약품 추천 없이 AI 후보 목록만으로 짧은 안내 문구를 만든다.
     */
    public String generatePreliminaryComment(java.util.List<com.artifact.diagnosis.analysis.TopKItem> topK) {
        if (apiKey == null || apiKey.isBlank()) {
            return "AI 참고 소견을 생성할 수 없습니다 (API 키 미설정).";
        }

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

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("error")) {
                log.error("Gemini API 에러(예비분석): {}", root.at("/error/message").asText("알 수 없는 오류"));
                return "AI 참고 소견 생성에 실패했습니다. 의사의 확인 진료가 필요합니다.";
            }

            String text = root.at("/candidates/0/content/parts/0/text").asText("").trim();
            return text.isEmpty()
                    ? "AI 참고 소견을 받지 못했습니다. 의사의 확인 진료가 필요합니다."
                    : text;
        } catch (Exception e) {
            log.error("Gemini API 호출 실패(예비분석): {}", e.getMessage());
            return "AI 참고 소견 생성 중 오류가 발생했습니다. 의사의 확인 진료가 필요합니다.";
        }
    }
}
