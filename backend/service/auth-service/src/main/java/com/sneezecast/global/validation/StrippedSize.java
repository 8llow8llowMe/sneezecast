package com.sneezecast.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 앞뒤 공백을 지운({@link String#strip()}) 문자열의 길이를 검사한다. 저장 전에 공백을 지우는 필드(닉네임)에 {@code @Size} 를 걸면 {@code " 가 "} 가
 * 3자로 통과하고 1자로 저장된다 — 저장값과 같은 기준으로 재야 한다.
 *
 * <p>길이는 <b>코드포인트</b> 기준이다 — 이모지 같은 보조 문자도 한 글자로 센다({@code @Size} 의 UTF-16 단위와 다르다). 카카오 닉네임 정규화와 같은
 * 기준이라, 카카오에서 받은 닉네임을 내 정보 수정으로 그대로 다시 보내도 통과한다.
 *
 * <p>null · 공백뿐인 값은 유효로 본다 — 필수 여부는 {@code @NotBlank} 가 맡는다(같은 의미를 두 제약으로 중복 검사하지 않는다).
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrippedSizeValidator.class)
public @interface StrippedSize {

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
