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
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final DrugMasterRepository drugMasterRepository;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key:}")
    private String apiKey;

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";

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
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("Gemini 응답 (status={}): {}", response.statusCode(), response.body());

            JsonNode root = objectMapper.readTree(response.body());

            // API 에러 응답 처리
            if (root.has("error")) {
                String errorMsg = root.at("/error/message").asText("알 수 없는 오류");
                log.error("Gemini API 에러: {}", errorMsg);
                return new PrescriptionCommentResponse("API 오류: " + errorMsg, "API 키와 모델 설정을 확인해주세요.");
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
}
