package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotifiableImportPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("값을 주지 않으면 질병관리청 시도 코드 01 ~ 18 · 실행당 호출 상한 100 이다")
    void fillsDefaults() {
        contextRunner.run(context -> {
            NotifiableImportProperties properties = context.getBean(NotifiableImportProperties.class);
            assertThat(properties.sidoCodes()).hasSize(18).startsWith("01").endsWith("18").doesNotContain("00");
            assertThat(properties.maxCallsPerRun()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("설정한 시도 목록은 순서대로 받는다")
    void bindsConfiguredSidoCodesInOrder() {
        contextRunner.withPropertyValues("notifiable-import.sido-codes=18,05,13", "notifiable-import.max-calls-per-run=20")
            .run(context -> {
                NotifiableImportProperties properties = context.getBean(NotifiableImportProperties.class);
                assertThat(properties.sidoCodes()).containsExactly("18", "05", "13");
                assertThat(properties.maxCallsPerRun()).isEqualTo(20);
            });
    }

    @ParameterizedTest(name = "sido-codes={0}")
    @ValueSource(strings = {"00", "1", "001", "ab", "01,01"})
    @DisplayName("시도 코드는 두 자리 숫자 · 00 아님 · 중복 없음이어야 한다")
    void rejectsInvalidSidoCodes(String value) {
        contextRunner.withPropertyValues("notifiable-import.sido-codes=" + value)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("notifiable-import.sido-codes"));
    }

    @ParameterizedTest(name = "max-calls-per-run={0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("실행당 호출 상한은 양수여야 한다")
    void rejectsNonPositiveMaxCalls(int value) {
        contextRunner.withPropertyValues("notifiable-import.max-calls-per-run=" + value)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("notifiable-import.max-calls-per-run"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotifiableImportProperties.class)
    static class TestConfig {
    }
}
