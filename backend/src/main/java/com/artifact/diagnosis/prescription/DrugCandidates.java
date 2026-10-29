package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.drug.DrugMaster;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 처방 코멘트 프롬프트의 '참고 약품 목록'을 상병에 맞춰 고른다.
 *
 * 예전에는 상병과 상관없이 약품 마스터에서 이름에 '연고'·'크림'이 든 앞쪽 5개씩을 넣었다. 실제 마스터
 * (resources/data/drug_master.xlsx)에서 그렇게 뽑히는 것은 치과용 연고·항진균 크림·국소마취 크림이라,
 * 여드름에 치과용 연고를, 피부염에 마취 크림을 권하는 코멘트가 나왔다. Gemini 든 Claude 든 "이 목록에서
 * 고르라"는 지시를 따르기 때문이다(Claude Haiku 5.5 실측).
 *
 * 그래서 AI 모델 라벨(피부 병변 8종·안면부 6종)이 가리키는 상병마다 1차로 쓰는 외용 성분을 정해 두고,
 * 그 성분이 든 외용제만 후보로 준다. 표에 없는 상병이면 목록을 비운다 — 엉뚱한 약을 주느니 약 없이
 * 처방 방향만 쓰게 하는 편이 낫다. 종양성 병변(흑색종·기저세포암·모반·지루각화증 등)은 약보다 조직검사·
 * 절제·경과 관찰이 먼저라 일부러 표에 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DrugCandidates {

    /**
     * KCD 앞 세 자리 → 성분명(약품명 괄호 안 표기). 앞에 둔 성분이 먼저 들어간다.
     * 주석의 라벨은 그 상병을 선입력하는 모델 클래스다(프론트 Clinic.tsx 의 DISEASE_TO_KCD_CODE).
     * 안면부 라벨이 많아, 얼굴에 쓰기 어려운 고역가 스테로이드는 넣지 않았다.
     */
    static final Map<String, List<String>> INGREDIENTS_BY_KCD = Map.of(
            "L70", List.of("아다팔렌", "과산화벤조일", "클린다마이신"),                                 // 여드름 (acne)
            "L71", List.of("메트로니다졸", "이버멕틴", "아젤라산"),                                     // 주사 (rosacea)
            "L21", List.of("케토코나졸", "시클로피록스", "데소나이드"),                                 // 지루피부염 (seb_derm)
            "L40", List.of("칼시포트리올", "데소나이드"),                                               // 건선 (psoriasis)
            "L20", List.of("타크로리무스", "피메크로리무스", "데소나이드", "메틸프레드니솔론아세포네이트"), // 아토피피부염 (atopic)
            "L30", List.of("프레드니카르베이트", "메틸프레드니솔론아세포네이트", "데소나이드"),          // 기타 피부염 (inflammatory)
            "L57", List.of("이미퀴모드")                                                              // 광선각화증 (akiec)
    );

    /** 프롬프트에 넣는 후보 수. 상병이 여럿이어도 이만큼만 — 목록이 길면 고르는 품질이 떨어진다. */
    static final int MAX_CANDIDATES = 10;

    /** 한 성분에서 넣는 제품 수. 같은 성분의 제네릭이 목록을 다 차지하지 않게 한다. */
    private static final int PER_INGREDIENT = 2;

    /** 제품 수를 채우기 전에 아래 제외 규칙으로 빠질 것까지 감안해 넉넉히 읽는다. */
    private static final int FETCH = 40;

    /** 외용제 이름에 섞여 나오는 다른 경로의 제형(질·안과·구강·치과용). */
    private static final Pattern OTHER_ROUTE = Pattern.compile("치과|질정|질겔|질크림|질연고|안연고|점안|구강|좌제");

    /** "로멕스겔(메트로니다졸)_(75mg/10g)" 처럼 포장 단위만 다른 행을 한 제품으로 묶는다. */
    private static final Pattern PACKAGE_SUFFIX = Pattern.compile("_\\(.*$|\\s*\\(수출명.*$");

    private final DrugMasterRepository drugMasterRepository;

    /**
     * 성분별 조회 결과. 약품 마스터는 기동 때 한 번 적재되고 바뀌지 않으므로 다시 읽지 않는다.
     * 비어 있는 결과는 담지 않는다 — 첫 기동의 백그라운드 적재가 끝나기 전에 들어온 요청이 빈 목록을 굳히면 안 된다.
     */
    private final Map<String, List<String>> byIngredient = new ConcurrentHashMap<>();

    /**
     * @param kcdCodes 주상병을 맨 앞에 둔 상병 코드들(점 없이, 예: L700).
     * @return "약품명(처방코드)" 목록. 맞는 성분이 없으면 비어 있다.
     */
    public List<String> forDiseases(List<String> kcdCodes) {
        Set<String> ingredients = new LinkedHashSet<>();
        for (String code : kcdCodes) {
            if (code == null) continue;
            String key = code.replace(".", "").trim().toUpperCase();
            if (key.length() < 3) continue;
            ingredients.addAll(INGREDIENTS_BY_KCD.getOrDefault(key.substring(0, 3), List.of()));
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String ingredient : ingredients) {
            for (String drug : products(ingredient)) {
                if (picked.size() >= MAX_CANDIDATES) return new ArrayList<>(picked);
                picked.add(drug);
            }
        }
        return new ArrayList<>(picked);
    }

    private List<String> products(String ingredient) {
        List<String> cached = byIngredient.get(ingredient);
        if (cached != null) return cached;

        Map<String, String> byProduct = new LinkedHashMap<>();
        for (DrugMaster drug : drugMasterRepository.findTopicalByIngredient(ingredient, PageRequest.of(0, FETCH))) {
            if (byProduct.size() >= PER_INGREDIENT) break;
            if (OTHER_ROUTE.matcher(drug.getNameKr()).find()) continue;
            String product = PACKAGE_SUFFIX.matcher(drug.getNameKr()).replaceAll("").trim();
            byProduct.putIfAbsent(product, product + "(" + drug.getCode() + ")");
        }
        List<String> found = List.copyOf(byProduct.values());
        if (!found.isEmpty()) byIngredient.put(ingredient, found);
        return found;
    }
}
