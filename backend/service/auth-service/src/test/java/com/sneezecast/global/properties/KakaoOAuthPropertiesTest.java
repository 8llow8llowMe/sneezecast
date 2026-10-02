package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KakaoOAuthPropertiesTest {

    private static final String[] VALID = {
        "oauth.kakao.client-id=kakao-client-id", "oauth.kakao.client-secret=kakao-secret", "oauth.kakao.redirect-uri=https://dev.sneezecast.com/cb",
        "oauth.kakao.authorize-uri=https://kauth.kakao.com/oauth/authorize", "oauth.kakao.token-uri=https://kauth.kakao.com/oauth/token",
        "oauth.kakao.user-info-uri=https://kapi.kakao.com/v2/user/me", "oauth.kakao.connect-timeout=PT2S", "oauth.kakao.read-timeout=PT3S"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("값이 바인딩되고 앞뒤 공백을 지운다")
    void bindsValues() {
        runner.withPropertyValues(VALID).withPropertyValues("oauth.kakao.client-id= kakao-client-id ").run(context -> {
            KakaoOAuthProperties properties = context.getBean(KakaoOAuthProperties.class);
            assertThat(properties.clientId()).isEqualTo("kakao-client-id");
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    @DisplayName("앱 키 · 시크릿 · 콜백 주소가 없거나 비거나 env 자리표시자가 풀리지 않았으면 기동에서 실패한다 — 메시지에는 키 · 환경변수 이름만 있다")
    void missingRequiredValuesFailStartup() {
        runner.withPropertyValues(VALID).withPropertyValues("oauth.kakao.client-secret=").run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("oauth.kakao.client-secret").hasMessageContaining("KAKAO_CLIENT_SECRET"));
        runner.withPropertyValues(VALID).withPropertyValues("oauth.kakao.client-id=${KAKAO_CLIENT_ID}").run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("KAKAO_CLIENT_ID"));

        assertThatThrownBy(() -> properties(null, "secret", "https://cb")).isInstanceOf(IllegalStateException.class).hasMessageContaining("KAKAO_CLIENT_ID");
        assertThatThrownBy(() -> properties("id", "secret", "  ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("KAKAO_REDIRECT_URI");
        assertThatThrownBy(() -> properties("id", "top-secret-value", "")).isInstanceOf(IllegalStateException.class)
            .message().doesNotContain("top-secret-value");
    }

    @Test
    @DisplayName("timeout 이 0 이하면 기동에서 실패한다")
    void nonPositiveTimeoutFailsStartup() {
        assertThatThrownBy(() -> new KakaoOAuthProperties("id", "secret", "https://cb", "https://a", "https://t", "https://u", Duration.ZERO, Duration.ofSeconds(3)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("oauth.kakao.connect-timeout");
        assertThatThrownBy(() -> new KakaoOAuthProperties("id", "secret", "https://cb", "https://a", "https://t", "https://u", Duration.ofSeconds(2), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("oauth.kakao.read-timeout");
    }

    @Test
    @DisplayName("toString 은 시크릿을 가린다")
    void toStringMasksSecret() {
        assertThat(properties("id", "top-secret-value", "https://cb").toString()).doesNotContain("top-secret-value").contains("clientSecret=****");
    }

    private static KakaoOAuthProperties properties(String clientId, String clientSecret, String redirectUri) {
        return new KakaoOAuthProperties(clientId, clientSecret, redirectUri, "https://a", "https://t", "https://u", Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    @EnableConfigurationProperties(KakaoOAuthProperties.class)
    static class PropertiesConfig {
    }
}
