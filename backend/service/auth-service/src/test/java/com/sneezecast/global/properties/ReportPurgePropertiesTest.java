package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReportPurgePropertiesTest {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final Duration ONE_YEAR = Duration.ofDays(365);
    private static final String CRON = "0 30 4 * * *";

    @Test
    @DisplayName("값이 바인딩된다 — 보관 기간은 일 단위 ISO-8601(P365D)로 받는다")
    void bindsValues() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues("auth.report-purge.scheduler-enabled=true", "auth.report-purge.initial-delay=PT1M", "auth.report-purge.fixed-delay=PT5M",
                "auth.report-purge.completion-margin=PT5M", "auth.report-purge.batch-size=50", "auth.report-purge.alert-attempt-threshold=12",
                "auth.report-purge.retention=P365D", "auth.report-purge.cleanup-cron=0 30 4 * * *")
            .run(context -> {
                ReportPurgeProperties properties = context.getBean(ReportPurgeProperties.class);
                assertThat(properties).isEqualTo(new ReportPurgeProperties("true", Duration.ofMinutes(1), FIVE_MINUTES, FIVE_MINUTES, 50, 12, ONE_YEAR, CRON));
            });
    }

    @Test
    @DisplayName("첫 회차 대기는 0 을 허용한다")
    void zeroInitialDelayAllowed() {
        assertThatCode(() -> new ReportPurgeProperties("true", Duration.ZERO, FIVE_MINUTES, FIVE_MINUTES, 50, 12, ONE_YEAR, CRON)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("0 이하 · 빠진 값과 잘못된 cron 은 기동에서 실패한다 — 메시지에 설정 키가 들어간다")
    void invalidValuesFailStartup() {
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ofSeconds(-1), FIVE_MINUTES, FIVE_MINUTES, 50, 12, ONE_YEAR, CRON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.initial-delay");
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ZERO, Duration.ZERO, FIVE_MINUTES, 50, 12, ONE_YEAR, CRON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.fixed-delay");
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ZERO, FIVE_MINUTES, null, 50, 12, ONE_YEAR, CRON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.completion-margin");
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ZERO, FIVE_MINUTES, FIVE_MINUTES, 0, 12, ONE_YEAR, CRON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.batch-size");
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ZERO, FIVE_MINUTES, FIVE_MINUTES, 50, 0, ONE_YEAR, CRON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.alert-attempt-threshold");
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ZERO, FIVE_MINUTES, FIVE_MINUTES, 50, 12, Duration.ofDays(-1), CRON))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.retention");
        assertThatThrownBy(() -> new ReportPurgeProperties("true", Duration.ZERO, FIVE_MINUTES, FIVE_MINUTES, 50, 12, ONE_YEAR, "30 4 * * *"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.cleanup-cron");
    }

    @ParameterizedTest(name = "scheduler-enabled=\"{0}\"")
    @ValueSource(strings = {"true", "TRUE", "True"})
    @DisplayName("스위치는 대소문자를 무시한 true 면 켜짐이다 — @ConditionalOnProperty 와 같은 판정")
    void trueIgnoringCaseIsOn(String value) {
        assertThat(withSwitch(value).isSchedulerOn()).isTrue();
    }

    @Test
    @DisplayName("false(대소문자 무시)와 키 없음(null)은 꺼짐이다")
    void falseOrMissingIsOff() {
        assertThat(withSwitch("false").isSchedulerOn()).isFalse();
        assertThat(withSwitch("FALSE").isSchedulerOn()).isFalse();
        assertThat(withSwitch(null).isSchedulerOn()).isFalse();
    }

    @ParameterizedTest(name = "scheduler-enabled=\"{0}\"")
    @ValueSource(strings = {"", " ", "yes", "1", "on", "ture"})
    @DisplayName("true · false 가 아닌 스위치 값은 기동 실패다 — 오타가 법적 파기를 조용히 끄지 않게")
    void ambiguousSwitchFailsStartup(String value) {
        assertThatThrownBy(() -> withSwitch(value))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.report-purge.scheduler-enabled");
    }

    private static ReportPurgeProperties withSwitch(String value) {
        return new ReportPurgeProperties(value, Duration.ZERO, FIVE_MINUTES, FIVE_MINUTES, 50, 12, ONE_YEAR, CRON);
    }

    @EnableConfigurationProperties(ReportPurgeProperties.class)
    static class PropertiesConfig {
    }
}
