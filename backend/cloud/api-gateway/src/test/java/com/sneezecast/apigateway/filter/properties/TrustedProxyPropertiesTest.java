package com.sneezecast.apigateway.filter.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.apigateway.config.TrustedProxyPropertiesConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 신뢰 프록시 목록 검사. 틀린 값으로 뜨면 nginx 경유 요청까지 nginx IP 한 키를 나눠 쓰게 되므로 기동에서 막는다.
 */
class TrustedProxyPropertiesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"192.168.0.12", "10.0.0.0/8", "0.0.0.0/0", "192.168.0.12/32", "::1", "2001:db8::/32", "2001:db8::1/128"})
    @DisplayName("리터럴 IPv4 · IPv6 와 범위 안의 CIDR 은 통과한다")
    void acceptsLiteralAddressesAndCidr(String entry) {
        assertThat(new TrustedProxyProperties(List.of(entry)).toRules()).hasSize(1);
    }

    @Test
    @DisplayName("항목 앞뒤 공백은 지운다 — 콤마 뒤에 띄어 쓴 env 값")
    void trimsEntries() {
        assertThat(new TrustedProxyProperties(List.of(" 192.168.0.12 ", "10.0.0.0/8")).trustedProxies())
            .containsExactly("192.168.0.12", "10.0.0.0/8");
    }

    @Test
    @DisplayName("목록이 없거나 비면 거부한다")
    void rejectsMissingOrEmptyList() {
        assertThatThrownBy(() -> new TrustedProxyProperties(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("GATEWAY_TRUSTED_PROXIES");
        assertThatThrownBy(() -> new TrustedProxyProperties(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("GATEWAY_TRUSTED_PROXIES");
    }

    @Test
    @DisplayName("빈 항목은 거부한다 — 끝에 남은 콤마 같은 실수")
    void rejectsBlankEntry() {
        assertThatThrownBy(() -> new TrustedProxyProperties(List.of("192.168.0.12", " ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("빈 항목");
        assertThatThrownBy(() -> new TrustedProxyProperties(Arrays.asList("192.168.0.12", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("빈 항목");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"localhost", "nginx", "nginx.internal/24", "192.168.0", "192.168.0.256", "999.1.1.1/8"})
    @DisplayName("호스트명과 IP 가 아닌 값은 DNS 를 타기 전에 거부한다")
    void rejectsHostnamesAndInvalidAddresses(String entry) {
        assertThatThrownBy(() -> new TrustedProxyProperties(List.of(entry)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("호스트명은 받지 않습니다");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"192.168.0.0/33", "192.168.0.0/-1", "192.168.0.0/", "192.168.0.0/abc", "192.168.0.0/+8", "2001:db8::/129"})
    @DisplayName("prefix 가 숫자가 아니거나 범위 밖이면 거부한다 (IPv4 0~32, IPv6 0~128)")
    void rejectsInvalidPrefix(String entry) {
        assertThatThrownBy(() -> new TrustedProxyProperties(List.of(entry)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prefix");
    }

    @Test
    @DisplayName("콤마 구분 env 값이 목록으로 바인딩된다")
    void bindsCommaSeparatedValue() {
        new ApplicationContextRunner()
            .withUserConfiguration(TrustedProxyPropertiesConfig.class)
            .withPropertyValues("gateway.trusted-proxies=192.168.0.12, 10.0.0.0/8")
            .run(context -> assertThat(context.getBean(TrustedProxyProperties.class).trustedProxies())
                .containsExactly("192.168.0.12", "10.0.0.0/8"));
    }

    @Test
    @DisplayName("값이 비어 있으면 게이트웨이 컨텍스트가 뜨지 않는다")
    void emptyValueFailsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(TrustedProxyPropertiesConfig.class)
            .withPropertyValues("gateway.trusted-proxies=")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("GATEWAY_TRUSTED_PROXIES"));
    }

    @Test
    @DisplayName("호스트명이 섞이면 게이트웨이 컨텍스트가 뜨지 않는다")
    void hostnameFailsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(TrustedProxyPropertiesConfig.class)
            .withPropertyValues("gateway.trusted-proxies=192.168.0.12,nginx")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("nginx"));
    }
}
