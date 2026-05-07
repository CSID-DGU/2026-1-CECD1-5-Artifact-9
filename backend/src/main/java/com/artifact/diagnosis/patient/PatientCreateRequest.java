package com.artifact.diagnosis.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 환자 등록 요청 DTO. 접수 화면의 폼 데이터가 그대로 들어온다.
 *
 *   필수: name
 *   선택: birthDate, gender, phone, memo
 */
public record PatientCreateRequest(

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이내여야 합니다.")
        String name,

        @Past(message = "생년월일은 과거 날짜여야 합니다.")
        LocalDate birthDate,

        Gender gender,

        @Size(max = 20, message = "전화번호는 20자 이내여야 합니다.")
        String phone,

        String memo
) {}
