package com.sneezecast.domainlayer.aggregate.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AggregatePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("application.yml 의 기본값이 시안 값으로 바인딩된다 — 최소 표본 100, 3%p · 8%p, 급변 50%, 기준선 직전 8주 중 4주")
    void bindsDefaultsFromApplicationYml() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context.getBean(AggregateProperties.class).toRule())
                .isEqualTo(new AggregateRule("2026-10-08", 100, 50, 3, 8, 4, 8)));
    }

    @Test
    @DisplayName("잘못된 값은 기동에서 실패한다 — high-delta-pp 가 slight-delta-pp 이하")
    void invalidValueFailsStartup() {
        runner.withPropertyValues("aggregate.rule-version=v1", "aggregate.min-sample=100", "aggregate.unstable-change-percent=50",
                "aggregate.slight-delta-pp=3", "aggregate.high-delta-pp=3", "aggregate.baseline-weeks=4", "aggregate.baseline-lookback-weeks=8")
            .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new AggregateProperties("v1", 100, 50, 3, 3, 4, 8))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aggregate.high-delta-pp");
    }

    @Test
    @DisplayName("값이 빠지면 기동에서 실패한다 — 0 이나 빈 버전이 조용히 들어가지 않는다")
    void missingValuesFailStartup() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("aggregate.min-sample=100", "aggregate.unstable-change-percent=50", "aggregate.slight-delta-pp=3",
                "aggregate.high-delta-pp=8", "aggregate.baseline-weeks=4", "aggregate.baseline-lookback-weeks=8")
            .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(AggregateProperties.class)
    static class PropertiesConfig {
    }
}
