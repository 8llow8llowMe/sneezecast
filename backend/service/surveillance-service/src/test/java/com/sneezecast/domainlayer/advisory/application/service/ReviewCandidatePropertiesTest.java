package com.sneezecast.domainlayer.advisory.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.advisory.domain.model.ReviewCandidateRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReviewCandidatePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("application.yml 의 값이 결정값으로 바인딩된다 — 급증 30% · 반복 20% (2026-10-11)")
    void bindsDefaultsFromApplicationYml() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context.getBean(ReviewCandidateProperties.class).toRule()).isEqualTo(new ReviewCandidateRule(30, 20)));
    }

    @Test
    @DisplayName("잘못된 값 · 빠진 값은 기동에서 실패한다 — 반복 100% 초과, 급증 0, 키 없음(0)")
    void invalidOrMissingValueFailsStartup() {
        runner.withPropertyValues("advisory.review-candidate.surge-change-percent=30", "advisory.review-candidate.repeat-revised-percent=101")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("advisory.review-candidate.surge-change-percent=0", "advisory.review-candidate.repeat-revised-percent=20")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("advisory.review-candidate.surge-change-percent=30")
            .run(context -> assertThat(context).hasFailed());
        runner.run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(ReviewCandidateProperties.class)
    static class PropertiesConfig {
    }
}
