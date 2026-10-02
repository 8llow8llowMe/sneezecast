package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoginAttemptPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("값이 바인딩된다 (ISO-8601 기간 포함)")
    void bindsValues() {
        contextRunner.withPropertyValues("auth.login.max-failure-count=5", "auth.login.lock-duration=PT10M", "auth.login.ip-max-failure-count=30",
            "auth.login.ip-window=PT1H").run(context -> {
                LoginAttemptProperties properties = context.getBean(LoginAttemptProperties.class);
                assertThat(properties.maxFailureCount()).isEqualTo(5);
                assertThat(properties.lockDuration()).isEqualTo(Duration.ofMinutes(10));
                assertThat(properties.ipMaxFailureCount()).isEqualTo(30);
                assertThat(properties.ipWindow()).isEqualTo(Duration.ofHours(1));
            });
    }

    @Test
    @DisplayName("0 이하 · 빠진 값은 기본값으로 바꾸지 않고 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void nonPositiveValuesFailStartup() {
        assertThatThrownBy(() -> new LoginAttemptProperties(0, Duration.ofMinutes(10), 30, Duration.ofHours(1)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login.max-failure-count");
        assertThatThrownBy(() -> new LoginAttemptProperties(5, Duration.ZERO, 30, Duration.ofHours(1)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login.lock-duration");
        assertThatThrownBy(() -> new LoginAttemptProperties(5, Duration.ofMinutes(10), -1, Duration.ofHours(1)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login.ip-max-failure-count");
        assertThatThrownBy(() -> new LoginAttemptProperties(5, Duration.ofMinutes(10), 30, null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.login.ip-window");
        contextRunner.withPropertyValues("auth.login.max-failure-count=5", "auth.login.lock-duration=PT10M", "auth.login.ip-max-failure-count=30")
            .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(LoginAttemptProperties.class)
    static class PropertiesConfig {
    }
}
