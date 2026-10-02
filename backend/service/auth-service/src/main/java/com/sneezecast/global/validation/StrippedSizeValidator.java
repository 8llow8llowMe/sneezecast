package com.sneezecast.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link StrippedSize} 검사기. 길이는 코드포인트 기준이다({@link String#codePointCount}) — 이모지도 한 글자다({@code @Size} 의 UTF-16 단위와 다르다). */
public class StrippedSizeValidator implements ConstraintValidator<StrippedSize, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(StrippedSize constraint) {
        this.min = constraint.min();
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String stripped = value.toString().strip();
        if (stripped.isEmpty()) {
            return true;
        }
        int length = stripped.codePointCount(0, stripped.length());
        return length >= min && length <= max;
    }
}
