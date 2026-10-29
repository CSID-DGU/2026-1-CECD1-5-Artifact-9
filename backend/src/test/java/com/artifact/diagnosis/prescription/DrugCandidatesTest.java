package com.artifact.diagnosis.prescription;

import com.artifact.diagnosis.drug.DrugMaster;
import com.artifact.diagnosis.drug.DrugMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 처방 코멘트의 참고 약품 후보가 상병에 맞게 뽑히는지 본다. 약품 행은 실제 약품 마스터(drug_master.xlsx)의
 * 이름 모양을 그대로 옮겼다 — 포장 단위 꼬리(_(…))와 다른 경로 제형(질크림)이 섞여 나온다.
 */
class DrugCandidatesTest {

    private final DrugMasterRepository repository = mock(DrugMasterRepository.class);
    private final DrugCandidates candidates = new DrugCandidates(repository);

    @Test
    @DisplayName("안면부 라벨(여드름)은 그 상병의 외용 성분 후보를 성분당 두 제품까지 받는다")
    void acneGetsAcneTopicals() {
        stub("아다팔렌", drug("057000841", "디투오겔(아다팔렌)_(10mg/10g)"), drug("641501330", "엠파렌겔(아다팔렌)"),
                drug("641701050", "아크리아겔(아다팔렌)"));
        stub("과산화벤조일", drug("641805490", "톡클리어겔2.5%(가수과산화벤조일)"));
        stub("클린다마이신", drug("648901060", "크레오신질크림2%(인산클린다마이신)"),
                drug("641803050", "클리시아외용액(클린다마이신포스페이트)"));

        assertThat(candidates.forDiseases(List.of("L700"))).containsExactly(
                "디투오겔(아다팔렌)(057000841)", "엠파렌겔(아다팔렌)(641501330)",
                "톡클리어겔2.5%(가수과산화벤조일)(641805490)",
                // 질크림은 피부 외용제가 아니다.
                "클리시아외용액(클린다마이신포스페이트)(641803050)");
    }

    @Test
    @DisplayName("포장 단위만 다른 행은 한 제품으로 묶는다 — 같은 약이 후보를 다 차지하지 않게")
    void packageSizesCollapseToOneProduct() {
        stub("메트로니다졸", drug("057000101", "로멕스겔(메트로니다졸)_(75mg/10g)"),
                drug("057000102", "로멕스겔(메트로니다졸)_(112.5mg/15g)"), drug("625500220", "로사케어겔(메트로니다졸)"));

        assertThat(candidates.forDiseases(List.of("L719"))).containsExactly(
                "로멕스겔(메트로니다졸)(057000101)", "로사케어겔(메트로니다졸)(625500220)");
    }

    @Test
    @DisplayName("주상병의 성분이 먼저 오고, 상병이 여럿이어도 후보는 정해진 수를 넘지 않는다")
    void primaryFirstAndCapped() {
        when(repository.findTopicalByIngredient(anyString(), any(Pageable.class))).thenAnswer(inv -> {
            String ingredient = inv.getArgument(0);
            return new ArrayList<>(List.of(drug("A-" + ingredient, ingredient + "크림"),
                    drug("B-" + ingredient, ingredient + "연고")));
        });

        List<String> picked = candidates.forDiseases(List.of("L20.9", "L700", "L219"));

        assertThat(picked).hasSize(DrugCandidates.MAX_CANDIDATES);
        assertThat(picked.get(0)).isEqualTo("타크로리무스크림(A-타크로리무스)");
    }

    @Test
    @DisplayName("종양성 병변과 표에 없는 상병은 후보가 없다 — 엉뚱한 약을 주느니 약 없이 쓰게 한다")
    void tumorsAndUnknownCodesGetNothing() {
        assertThat(candidates.forDiseases(List.of("C439", "D229", "L82", "B351"))).isEmpty();
        verify(repository, never()).findTopicalByIngredient(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("찾은 후보는 다시 조회하지 않고, 빈 결과는 담아 두지 않는다 — 약품 마스터 적재 전의 빈 목록이 굳으면 안 된다")
    void cachesOnlyNonEmptyResults() {
        when(repository.findTopicalByIngredient(eq("이미퀴모드"), any(Pageable.class)))
                .thenReturn(new ArrayList<>())
                .thenReturn(new ArrayList<>(List.of(drug("642502510", "알다라크림(이미퀴모드)"))));

        assertThat(candidates.forDiseases(List.of("L570"))).isEmpty();
        assertThat(candidates.forDiseases(List.of("L570"))).containsExactly("알다라크림(이미퀴모드)(642502510)");
        assertThat(candidates.forDiseases(List.of("L570"))).containsExactly("알다라크림(이미퀴모드)(642502510)");

        verify(repository, times(2)).findTopicalByIngredient(eq("이미퀴모드"), any(Pageable.class));
    }

    private void stub(String ingredient, DrugMaster... rows) {
        when(repository.findTopicalByIngredient(eq(ingredient), any(Pageable.class)))
                .thenReturn(new ArrayList<>(List.of(rows)));
    }

    private static DrugMaster drug(String code, String nameKr) {
        return DrugMaster.builder().code(code).nameKr(nameKr).build();
    }
}
