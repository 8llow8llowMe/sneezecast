package com.sneezecast.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link StrippedSize} 검사기. 길이는 {@code @Size} 와 같은 {@link String#length()} 기준이다. */
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
        return stripped.length() >= min && stripped.length() <= max;
    }
}
