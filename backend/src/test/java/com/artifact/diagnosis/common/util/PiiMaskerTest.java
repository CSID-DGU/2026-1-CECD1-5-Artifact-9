package com.artifact.diagnosis.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 LLM 으로 나가기 전에 무엇이 지워지는지를 고정한다.
 *
 * 이 테스트가 지키는 것은 "메모가 그대로 나가지 않는다" 는 성질이다. 정규식으로 거르는 방식이라
 * 모든 표기를 잡지는 못하지만, 실제 접수 메모에 가장 많이 섞이는 형태(이름·휴대폰·주민번호·이메일)는
 * 반드시 걸러야 한다. 잡지 못하는 경우가 있다는 사실 자체도 아래에 남겨 둔다.
 */
class PiiMaskerTest {

    @Test
    @DisplayName("환자 이름을 '환자'로 바꾼다")
    void masksPatientName() {
        assertThat(PiiMasker.mask("김철수 님 어제부터 가렵다고 하심", "김철수"))
                .isEqualTo("환자 님 어제부터 가렵다고 하심");
    }

    @Test
    @DisplayName("휴대폰·유선 번호를 지운다 — 하이픈 유무와 무관")
    void masksPhone() {
        assertThat(PiiMasker.mask("연락처 010-1234-5678", null)).isEqualTo("연락처 [연락처]");
        assertThat(PiiMasker.mask("연락처 01012345678", null)).isEqualTo("연락처 [연락처]");
        assertThat(PiiMasker.mask("병원 02-123-4567", null)).isEqualTo("병원 [연락처]");
    }

    @Test
    @DisplayName("주민등록번호와 이메일을 지운다")
    void masksRrnAndEmail() {
        assertThat(PiiMasker.mask("900101-1234567", null)).isEqualTo("[주민번호]");
        assertThat(PiiMasker.mask("a.b+tag@example.co.kr 로 회신", null)).isEqualTo("[이메일] 로 회신");
    }

    @Test
    @DisplayName("한 메모에 여러 종류가 섞여 있어도 모두 지운다")
    void masksMixed() {
        String masked = PiiMasker.mask(
                "이영희 환자 010-9876-5432, 851231-2345678, lee@test.com 확인 요망", "이영희");

        assertThat(masked).isEqualTo("환자 환자 [연락처], [주민번호], [이메일] 확인 요망");
        // 프롬프트에 실려 나가는 문자열에 원본 조각이 남지 않는지가 핵심이다.
        assertThat(masked).doesNotContain("이영희", "9876", "851231", "lee@test.com");
    }

    @Test
    @DisplayName("메모가 없거나 비어 있으면 null — 프롬프트에서 줄째로 빠진다")
    void returnsNullForEmpty() {
        assertThat(PiiMasker.mask(null, "김철수")).isNull();
        assertThat(PiiMasker.mask("   ", "김철수")).isNull();
    }

    @Test
    @DisplayName("이름을 모르면 이름만 남고 나머지는 지운다 — 환자 조회 실패 시의 동작")
    void withoutPatientNameStillMasksTheRest() {
        assertThat(PiiMasker.mask("김철수 010-1111-2222", null))
                .isEqualTo("김철수 [연락처]");
    }

    @Test
    @DisplayName("한 글자 이름은 바꾸지 않는다 — 흔한 글자를 지우면 문장이 망가진다")
    void ignoresSingleCharName() {
        assertThat(PiiMasker.mask("이 부위가 아프다고 함", "이"))
                .isEqualTo("이 부위가 아프다고 함");
    }
}
