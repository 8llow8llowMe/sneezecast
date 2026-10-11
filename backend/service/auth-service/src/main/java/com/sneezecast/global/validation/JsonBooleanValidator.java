package com.sneezecast.global.validation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link JsonBoolean} 검사기. 항목 없음(Java null)은 통과, JSON {@code true} / {@code false} 만 통과, 그 밖(JSON null · 문자열 · 숫자 · 객체 · 배열)은 실패다. */
public class JsonBooleanValidator implements ConstraintValidator<JsonBoolean, JsonNode> {

    @Override
    public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
        return value == null || value.isBoolean();
    }
}
