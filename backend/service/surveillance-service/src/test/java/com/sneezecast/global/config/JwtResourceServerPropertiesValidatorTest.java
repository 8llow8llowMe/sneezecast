package com.sneezecast.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.security.resourceserver.jwt.JwtResourceServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtResourceServerPropertiesValidatorTest {

    @Test
    @DisplayName("64바이트 키는 통과한다 — 경계값")
    void acceptsMinimumLengthKey() {
        assertThatCode(() -> JwtResourceServerPropertiesValidator.validate(properties("k".repeat(64))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("63바이트 키는 기동에서 실패하고, 메시지에 키 값을 싣지 않는다")
    void rejectsShortKeyWithoutLeakingIt() {
        String shortKey = "short-access-secret-" + "x".repeat(43);

        assertThatThrownBy(() -> JwtResourceServerPropertiesValidator.validate(properties(shortKey)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.security.jwt.resource.access-key")
            .hasMessageNotContaining(shortKey);
    }

    @Test
    @DisplayName("글자 수가 아니라 UTF-8 바이트로 잰다 — 한글 21자(63바이트)는 거부, 22자(66바이트)는 통과")
    void measuresUtf8Bytes() {
        assertThatThrownBy(() -> JwtResourceServerPropertiesValidator.validate(properties("가".repeat(21))))
            .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> JwtResourceServerPropertiesValidator.validate(properties("가".repeat(22))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키가 없거나 공백뿐이면 실패한다")
    void rejectsMissingOrBlankKey() {
        assertThatThrownBy(() -> JwtResourceServerPropertiesValidator.validate(properties(null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtResourceServerPropertiesValidator.validate(properties("")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtResourceServerPropertiesValidator.validate(properties(" ".repeat(64))))
            .isInstanceOf(IllegalStateException.class);
    }

    private static JwtResourceServerProperties properties(String accessKey) {
        return new JwtResourceServerProperties(accessKey);
    }
}
