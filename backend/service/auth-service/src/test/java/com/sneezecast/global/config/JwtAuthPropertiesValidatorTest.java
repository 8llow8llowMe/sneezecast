package com.sneezecast.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.security.auth.config.JwtAuthPropertiesConfig;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * auth 전용 만료 정책. 서명 키 길이 검사는 security-core {@code JwtAuthPropertiesTest} 가 덮는다 —
 * 여기서는 auth 가 import 하는 설정 조합에서 짧은 키가 실제로 기동을 막는지만 한 번 본다.
 */
class JwtAuthPropertiesValidatorTest {

    private static final String KEY_64_BYTES = "k".repeat(64);
    private static final Duration REFRESH = Duration.ofDays(14);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(JwtAuthPropertiesConfig.class, JwtAuthPropertiesValidator.class);

    @Test
    @DisplayName("access 만료 15분은 통과한다 — 경계값")
    void acceptsBoundaryValues() {
        assertThatCode(() -> JwtAuthPropertiesValidator.validate(properties(Duration.ofMinutes(15))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("access 만료가 15분을 넘으면 기동에서 실패한다 — 동의 철회 반영 지연의 상한")
    void rejectsAccessExpirationOverFifteenMinutes() {
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(Duration.ofMinutes(15).plusSeconds(1))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt.access-expiration");
    }

    @Test
    @DisplayName("만료가 비었거나 0 이하면 실패한다")
    void rejectsMissingOrNonPositiveExpiration() {
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(properties(Duration.ZERO)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtAuthPropertiesValidator.validate(new JwtAuthProperties(KEY_64_BYTES, Duration.ofMinutes(15), KEY_64_BYTES, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt.refresh-expiration");
    }

    @Test
    @DisplayName("설정이 올바르면 컨텍스트가 뜨고 검사기가 등록된다")
    void startsWithValidProperties() {
        contextRunner
            .withPropertyValues(jwtProperties(KEY_64_BYTES, "15m"))
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtAuthPropertiesValidator.class));
    }

    @Test
    @DisplayName("access 만료 16분이면 컨텍스트가 뜨지 않는다 — 검사기가 기동 시점에 돈다")
    void accessExpirationOverLimitFailsStartup() {
        contextRunner
            .withPropertyValues(jwtProperties(KEY_64_BYTES, "16m"))
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("jwt.access-expiration"));
    }

    @Test
    @DisplayName("63바이트 access key 면 컨텍스트가 뜨지 않고, 실패 메시지에 키 값이 없다 — security-core 바인딩 검사")
    void shortKeyFailsStartup() {
        String shortKey = "k".repeat(63);

        contextRunner
            .withPropertyValues(jwtProperties(shortKey, "15m"))
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.access-key")
                .hasMessageNotContaining(shortKey));
    }

    private static JwtAuthProperties properties(Duration accessExpiration) {
        return new JwtAuthProperties(KEY_64_BYTES, accessExpiration, KEY_64_BYTES, REFRESH);
    }

    private static String[] jwtProperties(String accessKey, String accessExpiration) {
        return new String[] {
            "jwt.access-key=" + accessKey,
            "jwt.access-expiration=" + accessExpiration,
            "jwt.refresh-key=" + KEY_64_BYTES,
            "jwt.refresh-expiration=14d"
        };
    }
}
