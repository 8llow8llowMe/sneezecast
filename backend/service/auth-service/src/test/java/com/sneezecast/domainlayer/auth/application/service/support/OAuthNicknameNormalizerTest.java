package com.sneezecast.domainlayer.auth.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthNicknameNormalizerTest {

    private static final String FALLBACK = "동네이웃\\d{4}";

    @Test
    @DisplayName("2~10자면 앞뒤 공백만 지운다")
    void keepsValidNickname() {
        assertThat(OAuthNicknameNormalizer.normalize("  재채기탐정 ")).isEqualTo("재채기탐정");
        assertThat(OAuthNicknameNormalizer.normalize("가나")).isEqualTo("가나");
        assertThat(OAuthNicknameNormalizer.normalize("가나다라마바사아자차")).isEqualTo("가나다라마바사아자차");
    }

    @Test
    @DisplayName("10자를 넘으면 앞 10자만 남기고, 자른 끝의 공백도 지운다")
    void truncatesLongNickname() {
        assertThat(OAuthNicknameNormalizer.normalize("가나다라마바사아자차카타")).isEqualTo("가나다라마바사아자차");
        assertThat(OAuthNicknameNormalizer.normalize("가나다라마바사아자 카타")).isEqualTo("가나다라마바사아자");
    }

    @Test
    @DisplayName("길이는 코드포인트 기준이다 — 이모지(보조 문자)를 반쪽으로 자르지 않고 한 글자로 센다")
    void countsCodePoints() {
        String emoji = "😀".repeat(12);
        String normalized = OAuthNicknameNormalizer.normalize(emoji);

        assertThat(normalized).isEqualTo("😀".repeat(10));
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(10);
        assertThat(OAuthNicknameNormalizer.normalize("😀😀")).isEqualTo("😀😀");
        assertThat(OAuthNicknameNormalizer.normalize("가나다라마바사아자😀😀")).isEqualTo("가나다라마바사아자😀");
    }

    @Test
    @DisplayName("없음 · 공백뿐 · 한 글자(이모지 하나 포함)면 동네이웃 + 숫자 4자리다")
    void fallsBackWhenTooShort() {
        assertThat(OAuthNicknameNormalizer.normalize(null)).matches(FALLBACK);
        assertThat(OAuthNicknameNormalizer.normalize("   ")).matches(FALLBACK);
        assertThat(OAuthNicknameNormalizer.normalize("가")).matches(FALLBACK);
        assertThat(OAuthNicknameNormalizer.normalize(" 😀 ")).matches(FALLBACK);
    }
}
