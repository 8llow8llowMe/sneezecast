package com.sneezecast.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 원본 JSON 값({@code JsonNode})이 {@code true} / {@code false} 리터럴인지 검사한다. Boot 기본 ObjectMapper 는 {@code Boolean} 필드에 문자열
 * {@code "true"} · 숫자 {@code 1} 을 boolean 으로 바꿔 넣고 JSON {@code null} 은 "보내지 않음" 과 구분하지 못한다 — 전역 설정을 바꾸지 않고 이
 * 필드만 엄격하게 받으려고, 필드를 {@code JsonNode} 로 받아 이 제약으로 본다.
 *
 * <p>Java {@code null}(본문에 항목이 없음)은 유효로 본다 — 필수 여부는 다른 제약이 맡는다. JSON {@code null} 은 {@code NullNode} 로 들어와 무효다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JsonBooleanValidator.class)
public @interface JsonBoolean {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
