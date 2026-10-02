package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PasswordResetPropertiesTest {

    @Test
    @DisplayName("값이 바인딩된다")
    void bindsValues() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues("auth.password-reset.token-ttl=PT20M")
            .run(context -> assertThat(context.getBean(PasswordResetProperties.class).tokenTtl()).isEqualTo(Duration.ofMinutes(20)));
    }

    @Test
    @DisplayName("0 이하 · 빠진 값은 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void nonPositiveValuesFailStartup() {
        assertThatThrownBy(() -> new PasswordResetProperties(Duration.ZERO))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.password-reset.token-ttl");
        assertThatThrownBy(() -> new PasswordResetProperties(Duration.ofMinutes(-1)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.password-reset.token-ttl");
        assertThatThrownBy(() -> new PasswordResetProperties(null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.password-reset.token-ttl");
    }

    @EnableConfigurationProperties(PasswordResetProperties.class)
    static class PropertiesConfig {
    }
}
