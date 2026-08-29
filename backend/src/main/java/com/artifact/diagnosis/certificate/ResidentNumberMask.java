package com.artifact.diagnosis.certificate;

import com.artifact.diagnosis.patient.Gender;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 문서에 찍을 주민등록번호 표시값을 만든다.
 *
 * 법정서식에는 주민등록번호 칸이 있지만 이 시스템은 주민등록번호를 받지도 저장하지도 않는다.
 * 안 가진 정보가 새어나갈 일이 없다는 게 가장 확실한 보호이고, 실제로 이 서비스가 하는 일
 * (피부 병변 판독 보조)에 주민등록번호가 필요한 지점이 없다.
 *
 * 대신 이미 가지고 있는 생년월일과 성별로 앞 6자리와 뒷자리 첫 글자까지만 구성하고
 * 나머지 6자리는 {@code *} 로 채운다. 사람이 신원을 확인하기에는 충분하고,
 * 유출돼도 원본 번호를 복원할 수는 없다.
 *
 * <pre>
 *   900101-1******   1990년생 남성
 *   030315-4******   2003년생 여성
 *   ******-*******   생년월일이 없는 경우
 * </pre>
 *
 * 계산을 프론트가 아니라 여기서 하는 이유는, 이 값이 발급 스냅샷
 * ({@link CertificateDocument#patientResidentNo})에 그대로 들어가야 재발급본이
 * 원본과 한 글자도 다르지 않게 나오기 때문이다.
 */
public final class ResidentNumberMask {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String UNKNOWN = "******-*******";

    private ResidentNumberMask() {}

    public static String of(LocalDate birthDate, Gender gender) {
        if (birthDate == null) {
            return UNKNOWN;
        }
        return birthDate.format(YYMMDD) + "-" + genderCode(birthDate.getYear(), gender) + "******";
    }

    /**
     * 주민등록번호 뒷자리 첫 글자.
     * 1900년대생은 남 1 / 여 2, 2000년대생은 남 3 / 여 4다.
     * 성별이 OTHER 이거나 출생연도가 이 범위 밖이면 추정하지 않고 {@code *} 로 둔다 —
     * 틀린 숫자를 찍는 것보다 비워두는 편이 낫다.
     */
    private static char genderCode(int birthYear, Gender gender) {
        if (gender == null || gender == Gender.OTHER) {
            return '*';
        }
        boolean male = gender == Gender.M;
        if (birthYear >= 1900 && birthYear <= 1999) {
            return male ? '1' : '2';
        }
        if (birthYear >= 2000 && birthYear <= 2099) {
            return male ? '3' : '4';
        }
        return '*';
    }
}
