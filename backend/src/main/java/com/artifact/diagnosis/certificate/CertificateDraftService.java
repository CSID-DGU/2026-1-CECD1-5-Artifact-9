package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.common.util.PiiMasker;
import com.artifact.diagnosis.gemini.GeminiClient;
import com.artifact.diagnosis.gemini.GeminiResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 증명서의 서술형 칸 초안을 만든다.
 *
 * 이 서비스가 건드리는 것은 소견·치료계획·의뢰사유뿐이다. 병명, KCD 코드, 약품명, 진료일,
 * 면허번호 같은 사실 항목은 프롬프트에 재료로만 들어가고 응답에서는 받지 않는다.
 * 모델이 병명 한 줄을 그럴듯하게 지어내면 그건 문서 초안이 아니라 허위진단서가 되기 때문이다.
 * 응답 스키마를 서술 필드로만 고정하는 것이 그 경계를 코드로 강제하는 방법이다.
 *
 * 환자 이름·연락처는 프롬프트에 넣지 않는다. 나이는 연령대로만 준다. 자유 서술인 메모는
 * {@link PiiMasker} 를 거친다 — 외부 서버 로그에 환자 신원을 남기지 않기 위해서다.
 *
 * 실패는 예외로 던지지 않고 {@link CertificateDraftResponse#unavailable} 로 돌려준다.
 * 초안은 타자를 대신 쳐주는 편의 기능이라, Gemini가 죽어도 의사가 직접 써서 발급할 수 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateDraftService {

    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;

    /**
     * 서술 칸 초안 생성.
     *
     * @param facts DB에서 읽은 사실 정보. 이 범위를 벗어난 내용은 프롬프트에 들어가지 않는다.
     */
    public CertificateDraftResponse draft(CertificateFacts facts, CertificateDraftRequest req) {
        CertificateType type = req.type();

        if (!type.isAiAssisted()) {
            return CertificateDraftResponse.unavailable(
                    type.getLabel() + "은(는) 서술 항목이 없어 AI 초안을 만들지 않습니다.");
        }
        if (!geminiClient.hasApiKey()) {
            return CertificateDraftResponse.unavailable("Gemini API 키가 설정되지 않았습니다. 직접 작성해 주세요.");
        }

        String prompt = buildPrompt(facts, req);

        // 응답 형식을 JSON 스키마로 못 박는다. 모델이 문단을 통째로 뱉거나 사실 항목을 끼워
        // 넣을 여지를 구조적으로 없애기 위해서다. temperature는 낮게 — 같은 진료면 같은 문장이 낫다.
        GeminiResult result = geminiClient.generate(prompt, "application/json", responseSchema(type), 0.2);
        if (!result.success()) {
            log.error("Gemini API 실패(증명서 초안): {}", result.errorMessage());
            String message = result.isOverloaded()
                    ? "AI 서버가 일시적으로 혼잡합니다. 직접 작성하거나 잠시 후 다시 시도해 주세요."
                    : "AI 초안 생성에 실패했습니다. 직접 작성해 주세요.";
            return CertificateDraftResponse.unavailable(message);
        }

        try {
            JsonNode draft = objectMapper.readTree(result.text());
            return new CertificateDraftResponse(
                    textOrNull(draft, "opinion"),
                    textOrNull(draft, "treatmentPlan"),
                    textOrNull(draft, "referralReason"),
                    result.model(),
                    true,
                    CertificateDraftResponse.DISCLAIMER
            );
        } catch (Exception e) {
            log.error("Gemini 증명서 초안 응답 파싱 실패: {}", e.getMessage());
            return CertificateDraftResponse.unavailable("AI 초안 생성에 실패했습니다. 직접 작성해 주세요.");
        }
    }

    /** 종류가 쓰는 서술 필드만 스키마에 넣는다 — 그 외 필드는 모델이 반환할 수단 자체가 없다. */
    private Map<String, Object> responseSchema(CertificateType type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : type.getAiFields()) {
            properties.put(field, Map.of("type", "STRING"));
        }
        return Map.of(
                "type", "OBJECT",
                "properties", properties,
                "required", type.getAiFields()
        );
    }

    private String buildPrompt(CertificateFacts facts, CertificateDraftRequest req) {
        CertificateType type = req.type();
        String patientName = facts.patient().getName();

        List<String> lines = new ArrayList<>();
        lines.add("- 환자: " + ageBand(facts.patient().getBirthDate()) + " "
                + genderWord(facts.patient().getGender()));
        lines.add("- 진료일: " + facts.visit().getVisitDate().toLocalDate());

        if (facts.diseases().isEmpty()) {
            lines.add("- 상병: 기록 없음");
        } else {
            lines.add("- 주상병: " + facts.diseases().stream()
                    .filter(CertificateDocument.DiseaseLine::primary)
                    .map(d -> d.name() + "(" + d.code() + ")")
                    .findFirst().orElse("기록 없음"));
            String secondary = facts.diseases().stream()
                    .filter(d -> !d.primary())
                    .map(d -> d.name() + "(" + d.code() + ")")
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
            lines.add("- 부상병: " + (secondary.isEmpty() ? "없음" : secondary));
        }

        if (!facts.drugs().isEmpty()) {
            String drugs = facts.drugs().stream()
                    .map(d -> d.name()
                            + (d.dosage() == null ? "" : " " + d.dosage())
                            + (d.durationDays() == null ? "" : " " + d.durationDays() + "일분"))
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + " / " + b);
            lines.add("- 처방: " + drugs);
        }

        if (facts.hasPrescription()) {
            if (facts.prescription().getRevisitRecommendedDate() != null) {
                lines.add("- 재진 예정일: " + facts.prescription().getRevisitRecommendedDate());
            }
            String notes = PiiMasker.mask(facts.prescription().getDoctorNotes(), patientName);
            if (notes != null) lines.add("- 의사 메모: " + notes);
        }

        String receptionMemo = PiiMasker.mask(facts.visit().getReceptionMemo(), patientName);
        if (receptionMemo != null) lines.add("- 접수 메모: " + receptionMemo);

        if (req.purpose() != null && !req.purpose().isBlank()) lines.add("- 용도: " + req.purpose());
        if (req.submitTo() != null && !req.submitTo().isBlank()) lines.add("- 제출처: " + req.submitTo());
        if (type == CertificateType.REFERRAL && req.referralTo() != null && !req.referralTo().isBlank()) {
            lines.add("- 의뢰 의료기관: " + req.referralTo());
        }

        return """
                당신은 피부과 의사의 %s 작성을 돕는 보조 시스템입니다.
                아래 진료기록만을 근거로 서술 항목의 초안을 작성하세요.

                [진료기록]
                %s

                [작성 규칙]
                - 진료기록에 없는 사실을 만들어내지 마세요. 병명, 약품명, 검사 결과, 날짜를 새로 지어내면 안 됩니다.
                - 진단명을 새로 붙이지 말고, 위에 적힌 상병만 근거로 쓰세요.
                - 치료 기간이나 완치 시점을 단정하지 말고 "경과에 따라", "추적 관찰이 필요함" 같은 표현을 쓰세요.
                - 환자 이름, 연락처, 주민등록번호를 문장에 넣지 마세요.
                - 진단서 문어체(~함, ~됨, ~필요함)로 쓰고, 각 항목은 3문장을 넘기지 마세요.
                %s
                """.formatted(type.getLabel(), String.join("\n", lines), fieldGuide(type));
    }

    /** 종류별로 각 칸에 무엇을 쓸지 알려준다. 같은 '소견'이라도 서류마다 요구되는 내용이 다르다. */
    private String fieldGuide(CertificateType type) {
        return switch (type) {
            case DIAGNOSIS -> """
                    - opinion: 현재 상태와 향후 치료에 대한 소견.
                    - treatmentPlan: 앞으로의 치료 방향(외용제 유지, 재진 필요 등).""";
            case MEDICAL_OPINION -> """
                    - opinion: 진료 경과와 현재 상태에 대한 의학적 소견. 제출처가 판단에 쓸 수 있도록 구체적으로.""";
            case REFERRAL -> """
                    - referralReason: 상급 의료기관에 의뢰하는 사유(정밀검사 필요, 전문 치료 요함 등).
                    - opinion: 현재까지의 진료 경과와 임상 소견.""";
            default -> "";
        };
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    /** 나이를 그대로 주지 않고 연령대로 뭉갠다 — 생년월일은 그 자체로 식별정보다. */
    private String ageBand(LocalDate birthDate) {
        if (birthDate == null) return "연령 미상";
        int age = Period.between(birthDate, LocalDate.now()).getYears();
        if (age < 10) return "10세 미만";
        if (age >= 90) return "90대 이상";
        return (age / 10 * 10) + "대";
    }

    private String genderWord(com.artifact.diagnosis.patient.Gender gender) {
        if (gender == null) return "성별 미상";
        return switch (gender) {
            case M -> "남성";
            case F -> "여성";
            case OTHER -> "성별 미상";
        };
    }
}
