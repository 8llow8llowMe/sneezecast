package com.sneezecast.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtAuthPropertiesValidatorTest {

    private static final String KEY_64_BYTES = "k".repeat(64);
    private static final Duration REFRESH = Duration.ofDays(14);

    @Test
    @DisplayName("access 만료 15분 · 64바이트 키는 통과한다 — 경계값")
    void acceptsBoundaryValues() {
        assertThatCode(() -> JwtAuthPropertiesValidator.validate(properties(KEY_64_BYTES, Duration.ofMinutes(15))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("access 만료가 15분을 넘으면 기동에서 실패한다 — 동의 철회 반영 지연의 상한")
    void rejectsAccessExpirationOverFifteenMinutes() {
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(KEY_64_BYTES, Duration.ofMinutes(15).plusSeconds(1))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt.access-expiration");
    }

    @Test
    @DisplayName("만료가 비었거나 0 이하면 실패한다")
    void rejectsMissingOrNonPositiveExpiration() {
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(KEY_64_BYTES, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(KEY_64_BYTES, Duration.ZERO)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(new JwtAuthProperties(KEY_64_BYTES, Duration.ofMinutes(15), KEY_64_BYTES, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt.refresh-expiration");
    }

    @Test
    @DisplayName("HS512 최소 길이보다 짧은 키는 실패하고, 메시지에 키 값을 싣지 않는다")
    void rejectsShortKeyWithoutLeakingIt() {
        String shortKey = "short-secret-" + "x".repeat(40);

        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(shortKey, Duration.ofMinutes(15))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt.access-key")
            .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(new JwtAuthProperties(KEY_64_BYTES, Duration.ofMinutes(15), null, REFRESH)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt.refresh-key");
    }

    private static JwtAuthProperties properties(String accessKey, Duration accessExpiration) {
        return new JwtAuthProperties(accessKey, accessExpiration, KEY_64_BYTES, REFRESH);
    }
}
