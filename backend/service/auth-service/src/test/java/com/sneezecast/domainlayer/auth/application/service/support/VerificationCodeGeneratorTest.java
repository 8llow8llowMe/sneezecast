package com.sneezecast.domainlayer.auth.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VerificationCodeGeneratorTest {

    private final VerificationCodeGenerator generator = new VerificationCodeGenerator();

    @Test
    @DisplayName("항상 숫자 6자리다 — 작은 수도 앞자리 0 으로 채운다")
    void alwaysSixDigits() {
        IntStream.range(0, 2_000).mapToObj(i -> generator.generate())
            .forEach(code -> assertThat(code).matches("[0-9]{6}"));
    }
}
