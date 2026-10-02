package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthSessionPropertiesTest {

    @Test
    @DisplayName("값이 바인딩된다")
    void bindsValues() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues("auth.session.max-devices=5", "auth.session.rotation-grace=PT10S")
            .run(context -> {
                AuthSessionProperties properties = context.getBean(AuthSessionProperties.class);
                assertThat(properties.maxDevices()).isEqualTo(5);
                assertThat(properties.rotationGrace()).isEqualTo(Duration.ofSeconds(10));
            });
    }

    @Test
    @DisplayName("0 이하 · 빠진 값은 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void nonPositiveValuesFailStartup() {
        assertThatThrownBy(() -> new AuthSessionProperties(0, Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.session.max-devices");
        assertThatThrownBy(() -> new AuthSessionProperties(5, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.session.rotation-grace");
        assertThatThrownBy(() -> new AuthSessionProperties(5, null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.session.rotation-grace");
    }

    @EnableConfigurationProperties(AuthSessionProperties.class)
    static class PropertiesConfig {
    }
}
