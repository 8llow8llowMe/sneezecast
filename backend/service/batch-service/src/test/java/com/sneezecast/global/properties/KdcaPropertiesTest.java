package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class KdcaPropertiesTest {

    private static final String SERVICE_KEY = "fake-kdca-key+/=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("값을 주지 않으면 기본값이 채워지고, 인증키가 없어도 기동한다")
    void fillsDefaultsAndStartsWithoutServiceKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            KdcaProperties properties = context.getBean(KdcaProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("https://apis.data.go.kr/1790387/EIDAPIService");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.pageSize()).isEqualTo(5000);
            assertThat(properties.maxPagesPerRequest()).isEqualTo(5);
            assertThat(properties.hasServiceKey()).isFalse();
            assertThat(properties.serviceKeyLooksEncoded()).isFalse();
        });
    }

    @Test
    @DisplayName("Decoding 값(+ / =)은 인코딩된 값으로 보지 않고, % 가 있으면 Encoding 값으로 본다")
    void detectsEncodedServiceKey() {
        assertThat(properties(SERVICE_KEY).hasServiceKey()).isTrue();
        assertThat(properties(SERVICE_KEY).serviceKeyLooksEncoded()).isFalse();
        assertThat(properties("fake-kdca-key%2B%2F%3D").serviceKeyLooksEncoded()).isTrue();
        assertThat(properties(" ").hasServiceKey()).isFalse();
        assertThat(properties(null).hasServiceKey()).isFalse();
    }

    @Test
    @DisplayName("toString 은 인증키 값을 가린다")
    void toStringMasksServiceKey() {
        String text = properties(SERVICE_KEY).toString();

        assertThat(text).doesNotContain("fake-kdca-key").contains("serviceKey=****", "pageSize=5000");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"kdca.page-size=0", "kdca.max-pages-per-request=0", "kdca.read-timeout=0s", "kdca.connect-timeout=-1s", "kdca.base-url= "})
    @DisplayName("0 이하 · 빈 값은 기동을 세운다 — 메시지에는 설정 키 이름만 있다")
    void rejectsInvalidValues(String property) {
        String key = property.substring(0, property.indexOf('='));
        contextRunner.withPropertyValues(property, "kdca.service-key=" + SERVICE_KEY)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining(key).hasMessageNotContaining("fake-kdca-key"));
    }

    private static KdcaProperties properties(String serviceKey) {
        return new KdcaProperties("https://kdca.test", serviceKey, Duration.ofSeconds(3), Duration.ofSeconds(30), 5000, 5);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KdcaProperties.class)
    static class TestConfig {
    }
}
