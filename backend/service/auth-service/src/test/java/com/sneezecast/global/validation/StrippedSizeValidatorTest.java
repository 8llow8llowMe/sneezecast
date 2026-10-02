package com.sneezecast.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 닉네임 규칙(2~10자)과 같은 제약으로 본다. 길이는 앞뒤 공백을 지운 값의 코드포인트 수다 — 카카오 닉네임 정규화와 같은 기준. */
class StrippedSizeValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("이모지 하나(코드포인트 1 · UTF-16 2)는 1자라 짧다")
    void singleEmojiIsTooShort() {
        assertThat(valid("😀")).isFalse();
        assertThat(valid(" 😀 ")).isFalse();
    }

    @Test
    @DisplayName("코드포인트 9 · UTF-16 11 인 값은 10자 이하라 통과한다 — UTF-16 으로 재면 막혔다")
    void codePointsNotUtf16Units() {
        String nickname = "가나다라마바사😀😀";
        assertThat(nickname.length()).isEqualTo(11);
        assertThat(nickname.codePointCount(0, nickname.length())).isEqualTo(9);

        assertThat(valid(nickname)).isTrue();
        assertThat(valid("😀".repeat(10))).isTrue();
        assertThat(valid("😀".repeat(11))).isFalse();
    }

    @Test
    @DisplayName("앞뒤 공백을 지운 값으로 잰다 — ' 가 ' 는 1자라 짧고, null · 공백뿐은 @NotBlank 몫이라 통과로 본다")
    void measuresStrippedValue() {
        assertThat(valid(" 가 ")).isFalse();
        assertThat(valid("  가나  ")).isTrue();
        assertThat(valid(null)).isTrue();
        assertThat(valid("   ")).isTrue();
    }

    private static boolean valid(String nickname) {
        return validator.validate(new Target(nickname)).isEmpty();
    }

    record Target(@StrippedSize(min = 2, max = 10, message = "TEST_101:닉네임 길이") String nickname) {

    }
}
