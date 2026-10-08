package com.sneezecast.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BatchSchedulePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("값을 주지 않으면 Asia/Seoul · 6시간 · 전수신고 화 05:00 · 표본감시 금 06:00 이 채워진다")
    void fillsDefaultsWhenAbsent() {
        contextRunner.run(context -> {
            BatchScheduleProperties properties = context.getBean(BatchScheduleProperties.class);
            assertThat(properties.timeZone()).isEqualTo("Asia/Seoul");
            assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
            assertThat(properties.staleRunningAfter()).isEqualTo(Duration.ofHours(6));
            assertThat(properties.notifiableCron()).isEqualTo("0 0 5 ? * TUE");
            assertThat(properties.sentinelCron()).isEqualTo("0 0 6 ? * FRI");
        });
    }

    @Test
    @DisplayName("전수신고 cron 은 설정한 값을 받고, 빈 값은 기본값으로 접는다")
    void bindsNotifiableCron() {
        contextRunner.withPropertyValues("batch.schedule.notifiable-cron=0 30 6 ? * WED")
            .run(context -> assertThat(context.getBean(BatchScheduleProperties.class).notifiableCron()).isEqualTo("0 30 6 ? * WED"));
        contextRunner.withPropertyValues("batch.schedule.notifiable-cron=")
            .run(context -> assertThat(context.getBean(BatchScheduleProperties.class).notifiableCron())
                .isEqualTo(BatchScheduleProperties.DEFAULT_NOTIFIABLE_CRON));
    }

    @Test
    @DisplayName("표본감시 cron 은 설정한 값을 받고, 빈 값은 기본값으로 접는다")
    void bindsSentinelCron() {
        contextRunner.withPropertyValues("batch.schedule.sentinel-cron=0 0 7 ? * SAT")
            .run(context -> assertThat(context.getBean(BatchScheduleProperties.class).sentinelCron()).isEqualTo("0 0 7 ? * SAT"));
        contextRunner.withPropertyValues("batch.schedule.sentinel-cron= ")
            .run(context -> assertThat(context.getBean(BatchScheduleProperties.class).sentinelCron())
                .isEqualTo(BatchScheduleProperties.DEFAULT_SENTINEL_CRON));
    }

    @ParameterizedTest(name = "sentinel-cron={0}")
    @ValueSource(strings = {"0 6 * * FRI", "0 0 6 * * FRI", "every friday"})
    @DisplayName("표본감시 cron 도 Quartz cron 이 아니면 기동을 세운다")
    void rejectsInvalidSentinelCron(String cron) {
        contextRunner.withPropertyValues("batch.schedule.sentinel-cron=" + cron)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("batch.schedule.sentinel-cron"));
    }

    @ParameterizedTest(name = "notifiable-cron={0}")
    @ValueSource(strings = {"0 5 * * TUE", "0 0 5 * * TUE", "0 0 25 ? * TUE", "every tuesday"})
    @DisplayName("Quartz cron 이 아닌 값은 기동을 세운다 — 초 없는 5필드 · 일과 요일을 모두 지정한 식 포함")
    void rejectsInvalidNotifiableCron(String cron) {
        contextRunner.withPropertyValues("batch.schedule.notifiable-cron=" + cron)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("batch.schedule.notifiable-cron"));
    }

    @Test
    @DisplayName("설정한 값은 그대로 받는다 — 빈 시간대는 기본값으로 접는다")
    void bindsConfiguredValues() {
        contextRunner.withPropertyValues("batch.schedule.time-zone=UTC", "batch.schedule.stale-running-after=2h")
            .run(context -> {
                BatchScheduleProperties properties = context.getBean(BatchScheduleProperties.class);
                assertThat(properties.zoneId()).isEqualTo(ZoneId.of("UTC"));
                assertThat(properties.staleRunningAfter()).isEqualTo(Duration.ofHours(2));
            });
        contextRunner.withPropertyValues("batch.schedule.time-zone=")
            .run(context -> assertThat(context.getBean(BatchScheduleProperties.class).timeZone()).isEqualTo("Asia/Seoul"));
    }

    @Test
    @DisplayName("잘못된 시간대는 기동을 세운다 — TimeZone.getTimeZone 은 오타를 조용히 GMT 로 바꿔 발화가 9시간 밀린다")
    void rejectsInvalidTimeZone() {
        contextRunner.withPropertyValues("batch.schedule.time-zone=Asia/Seuol")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("Asia/Seuol"));
    }

    @ParameterizedTest(name = "stale-running-after={0}")
    @ValueSource(strings = {"0s", "-1h"})
    @DisplayName("0 이하의 stale-running-after 는 겹침 판정을 통째로 끄므로 기동을 세운다")
    void rejectsNonPositiveStaleRunningAfter(String value) {
        contextRunner.withPropertyValues("batch.schedule.stale-running-after=" + value)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("stale-running-after"));
    }

    @ParameterizedTest(name = "batch.schedule.enabled=\"{0}\"")
    @ValueSource(strings = {"", "ture", "yes"})
    @DisplayName("스케줄 스위치는 여기서 바인딩하지 않는다 — 불리언이 아닌 값이 와도 기동이 죽지 않는다 (판정은 ScheduleEnabledCondition)")
    void ignoresScheduleSwitch(String value) {
        contextRunner.withPropertyValues("batch.schedule.enabled=" + value)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(BatchScheduleProperties.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchScheduleProperties.class)
    static class TestConfig {
    }
}
