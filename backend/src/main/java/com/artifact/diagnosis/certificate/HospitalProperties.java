package com.artifact.diagnosis.certificate;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 발급 문서 머리말에 찍히는 의료기관 정보.
 *
 * 진단서·처방전 같은 법정서식은 의료기관 명칭·주소·전화번호·요양기관번호를 반드시 기재해야 한다.
 * 이 값들은 환자마다 달라지는 데이터가 아니라 이 서버가 어느 병원에 설치됐는지에 대한 설정이므로
 * DB 테이블이 아니라 설정 파일에서 읽는다. 병원이 바뀌면 {@code .env} 만 고치면 된다.
 */
@Component
@Getter
public class HospitalProperties {

    @Value("${hospital.name:아티팩트 피부과의원}")
    private String name;

    @Value("${hospital.address:}")
    private String address;

    @Value("${hospital.phone:}")
    private String phone;

    /** 요양기관번호 (건강보험 청구용 8자리). 법정서식의 기재 항목이다. */
    @Value("${hospital.registration-no:}")
    private String registrationNo;

    /** 문서 하단 직인 이미지 URL. 비워두면 '(직인생략)' 으로 표기된다. */
    @Value("${hospital.seal-image-url:}")
    private String sealImageUrl;
}
