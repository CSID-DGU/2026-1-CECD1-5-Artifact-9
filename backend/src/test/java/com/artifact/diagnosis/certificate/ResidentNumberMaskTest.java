package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.patient.Gender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문서에 찍히는 주민등록번호 표기 규칙을 고정한다.
 *
 * <p>이 시스템은 주민등록번호를 저장하지 않는다. 생년월일과 성별로 앞 6자리와 뒷자리 첫 글자까지만
 * 만들고 나머지는 가린다. 사람이 신원을 확인하기에는 충분하면서, 유출돼도 원본을 복원할 수 없다.
 */
class ResidentNumberMaskTest {

    @Test
    @DisplayName("1900년대생 — 남 1 / 여 2")
    void nineteenHundreds() {
        assertThat(ResidentNumberMask.of(LocalDate.of(1990, 1, 1), Gender.M)).isEqualTo("900101-1******");
        assertThat(ResidentNumberMask.of(LocalDate.of(1985, 12, 31), Gender.F)).isEqualTo("851231-2******");
    }

    @Test
    @DisplayName("2000년대생 — 남 3 / 여 4")
    void twoThousands() {
        assertThat(ResidentNumberMask.of(LocalDate.of(2003, 3, 15), Gender.F)).isEqualTo("030315-4******");
        assertThat(ResidentNumberMask.of(LocalDate.of(2015, 7, 9), Gender.M)).isEqualTo("150709-3******");
    }

    @Test
    @DisplayName("성별을 알 수 없으면 성별코드 자리도 가린다 — 틀린 숫자를 찍는 것보다 낫다")
    void unknownGender() {
        assertThat(ResidentNumberMask.of(LocalDate.of(1990, 1, 1), Gender.OTHER)).isEqualTo("900101-*******");
        assertThat(ResidentNumberMask.of(LocalDate.of(1990, 1, 1), null)).isEqualTo("900101-*******");
    }

    @Test
    @DisplayName("생년월일이 없으면 전체를 가린다")
    void unknownBirthDate() {
        assertThat(ResidentNumberMask.of(null, Gender.M)).isEqualTo("******-*******");
    }

    @Test
    @DisplayName("표기 길이는 실제 주민등록번호와 같아야 서식이 무너지지 않는다")
    void keepsRealNumberLayout() {
        String masked = ResidentNumberMask.of(LocalDate.of(1990, 1, 1), Gender.M);
        assertThat(masked).hasSize(14);
        assertThat(masked.charAt(6)).isEqualTo('-');
    }
}
