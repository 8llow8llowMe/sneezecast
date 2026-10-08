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

/** 표본감시 설정 두 가지 — 포털 호출({@code sentinel-portal}) · 적재({@code sentinel-import}). 둘 다 키 없이 기본값으로 돈다. */
class SentinelPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("값을 주지 않으면 포털 주소 · timeout 3s / 30s · 요청 간격 3s · 출처를 밝히는 User-Agent · 최근 8주 · 호출 상한 12 다")
    void fillsDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SentinelPortalProperties portal = context.getBean(SentinelPortalProperties.class);
            assertThat(portal.baseUrl()).isEqualTo("https://dportal.kdca.go.kr/pot/is/st");
            assertThat(portal.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(portal.readTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(portal.requestInterval()).isEqualTo(Duration.ofSeconds(3));
            assertThat(portal.userAgent()).isEqualTo("Mozilla/5.0 (compatible; sneezecast-batch; +https://www.sneezecast.com)");

            SentinelImportProperties sentinelImport = context.getBean(SentinelImportProperties.class);
            assertThat(sentinelImport.recentWeeks()).isEqualTo(8);
            assertThat(sentinelImport.maxCallsPerRun()).isEqualTo(12);
        });
    }

    @Test
    @DisplayName("요청 간격은 3초보다 길게는 바꿀 수 있다")
    void acceptsLongerRequestInterval() {
        contextRunner.withPropertyValues("sentinel-portal.request-interval=5s", "sentinel-import.recent-weeks=1", "sentinel-import.max-calls-per-run=2")
            .run(context -> {
                assertThat(context.getBean(SentinelPortalProperties.class).requestInterval()).isEqualTo(Duration.ofSeconds(5));
                assertThat(context.getBean(SentinelImportProperties.class).recentWeeks()).isEqualTo(1);
            });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "sentinel-portal.request-interval=2999ms",
        "sentinel-portal.request-interval=0s",
        "sentinel-portal.connect-timeout=0s",
        "sentinel-portal.read-timeout=-1s",
        "sentinel-portal.base-url= ",
        "sentinel-portal.user-agent= ",
        "sentinel-import.recent-weeks=0",
        "sentinel-import.max-calls-per-run=0"})
    @DisplayName("요청 간격 3초 미만 · 0 이하 timeout · 빈 값 · 0 이하 주 수 · 호출 상한은 기동을 세운다 — 메시지에 설정 키 이름이 있다")
    void rejectsInvalidValues(String property) {
        String key = property.substring(0, property.indexOf('='));
        contextRunner.withPropertyValues(property)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(key));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SentinelPortalProperties.class, SentinelImportProperties.class})
    static class TestConfig {
    }
}
