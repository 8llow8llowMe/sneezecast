package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OAuthLoginPropertiesTest {

    private static final Duration OK = Duration.ofMinutes(10);

    @Test
    @DisplayName("값이 바인딩된다")
    void bindsValues() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues("auth.oauth.state-ttl=PT5M", "auth.oauth.signup-ticket-ttl=PT20M", "auth.oauth.link-ticket-ttl=PT3M",
                "auth.oauth.authorize-ip-max-count=12", "auth.oauth.authorize-ip-window=PT15M")
            .run(context -> assertThat(context.getBean(OAuthLoginProperties.class))
                .isEqualTo(new OAuthLoginProperties(Duration.ofMinutes(5), Duration.ofMinutes(20), Duration.ofMinutes(3), 12, Duration.ofMinutes(15))));
    }

    @Test
    @DisplayName("0 이하 · 빠진 값은 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void nonPositiveValuesFailStartup() {
        assertThatThrownBy(() -> new OAuthLoginProperties(Duration.ZERO, OK, OK, 30, OK)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.oauth.state-ttl");
        assertThatThrownBy(() -> new OAuthLoginProperties(OK, Duration.ofMinutes(-1), OK, 30, OK)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.oauth.signup-ticket-ttl");
        assertThatThrownBy(() -> new OAuthLoginProperties(OK, OK, null, 30, OK)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.oauth.link-ticket-ttl");
        assertThatThrownBy(() -> new OAuthLoginProperties(OK, OK, OK, 0, OK)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.oauth.authorize-ip-max-count");
        assertThatThrownBy(() -> new OAuthLoginProperties(OK, OK, OK, 30, Duration.ZERO)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("auth.oauth.authorize-ip-window");
    }

    @Test
    @DisplayName("인가 IP 상한 설정이 빠지면 기동에서 실패한다 (기본값은 application.yml 이 정본)")
    void missingAuthorizeIpLimitFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues("auth.oauth.state-ttl=PT5M", "auth.oauth.signup-ticket-ttl=PT20M", "auth.oauth.link-ticket-ttl=PT3M")
            .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(OAuthLoginProperties.class)
    static class PropertiesConfig {
    }
}
