package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RegionInterestPropertiesTest {

    @Test
    @DisplayName("값이 바인딩된다")
    void bindsValue() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues("region.interest.max-count=3")
            .run(context -> assertThat(context.getBean(RegionInterestProperties.class).maxCount()).isEqualTo(3));
    }

    @Test
    @DisplayName("0 이하 · 빠진 값은 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void nonPositiveValueFailsStartup() {
        assertThatThrownBy(() -> new RegionInterestProperties(0))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("region.interest.max-count");
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(RegionInterestProperties.class)
    static class PropertiesConfig {
    }
}
