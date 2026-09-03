package com.artifact.diagnosis.docshare;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 열람 링크의 본인 확인 입력.
 *
 * <p>{@code LocalDate} 가 아니라 문자열로 받는다. 날짜 타입으로 받으면 형식이 조금만 어긋나도
 * Spring 의 역직렬화 단계에서 터지는데, 그건 {@code @RestControllerAdvice} 까지 오지 않고
 * 500 으로 나간다. 환자가 하이픈을 빼먹은 것뿐인데 "서버 내부 오류"가 뜨면 안 된다.
 *
 * <p>그래서 서비스가 숫자만 남겨 비교한다({@code DocumentShareService.normalizeBirthDate}).
 * {@code 1990-01-01}, {@code 19900101}, {@code 1990.01.01} 이 모두 같은 값이 된다.
 * 길이 상한은 그 정규화가 감당할 범위를 넘는 입력을 애초에 막기 위한 것이다.
 */
public record ShareVerificationRequest(

        @NotBlank(message = "생년월일을 입력해 주세요.")
        @Size(max = 20, message = "생년월일 형식이 올바르지 않습니다.")
        String birthDate
) {}
