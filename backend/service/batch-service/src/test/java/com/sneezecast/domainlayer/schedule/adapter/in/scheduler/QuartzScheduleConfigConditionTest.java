package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.job.NotifiableImportJobConfig;
import com.sneezecast.domainlayer.sentinelimport.adapter.in.batch.job.SentinelImportJobConfig;
import com.sneezecast.global.properties.BatchScheduleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 스케줄 조건은 두 가지 사고를 막는다 — 테스트 컨텍스트 · 개발자 PC 가 실제 적재를 시작하는 것과, 잡 하나만 돌리려 띄운 두 번째 JVM 이
 * 또 다른 잡을 띄우는 것. 조건이 컴파일로 검증되지 않으므로 스위치 조합을 직접 돌려 본다. <b>불리언이 아닌 값</b>도 조합에 넣는다 —
 * 배포에서 실제로 들어올 수 있고, 그때 기동이 죽으면 안 된다.
 *
 * <p>{@code QuartzAutoConfiguration} 을 함께 올려 실제 스케줄러의 시작 여부까지 본다. yml 처럼 {@code auto-startup=false} 를 주고,
 * 조건이 참일 때만 커스터마이저가 그것을 이기는지가 이 구성의 핵심이다. 트리거(전수신고 · 표본감시)도 조건을 따라 붙거나 빠진다. 스케줄러가 시작돼도
 * 발화하지 않게 cron 을 먼 미래(2099년)로 준다 — 이 컨텍스트에는 잡을 띄울 유스케이스가 없다.
 * application.yml 을 읽지 않으므로 스케줄러 이름이 달라 {@code BatchServiceApplicationTests} 의 스케줄러와 겹치지 않는다.
 */
class QuartzScheduleConfigConditionTest {

    private static final String NEVER_CRON = "0 0 0 1 1 ? 2099";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, QuartzAutoConfiguration.class))
        .withUserConfiguration(BatchSchedulePropertiesTestConfig.class, QuartzScheduleConfig.class)
        .withPropertyValues("spring.quartz.auto-startup=false", "batch.schedule.notifiable-cron=" + NEVER_CRON,
            "batch.schedule.sentinel-cron=" + NEVER_CRON);

    @Test
    @DisplayName("스케줄이 켜져 있고 수동 실행 JVM 이 아니면 커스터마이저가 auto-startup=false 를 이겨 스케줄러가 시작된다")
    void startsSchedulerWhenScheduleEnabled() {
        contextRunner.withPropertyValues("batch.schedule.enabled=true", "spring.batch.job.enabled=false")
            .run(context -> {
                assertThat(context).hasSingleBean(SchedulerFactoryBeanCustomizer.class);
                assertThat(context.getBean(Scheduler.class).isStarted()).isTrue();
                assertThat(context.getBean(Scheduler.class).checkExists(JobKey.jobKey(NotifiableImportJobConfig.JOB_NAME))).isTrue();
                assertThat(context.getBean(Scheduler.class).checkExists(JobKey.jobKey(SentinelImportJobConfig.JOB_NAME))).isTrue();
            });
    }

    @Test
    @DisplayName("spring.batch.job.enabled 를 주지 않아도 수동 실행이 아닌 것으로 보고 켠다")
    void treatsMissingManualLaunchSwitchAsResidentRun() {
        contextRunner.withPropertyValues("batch.schedule.enabled=true")
            .run(context -> assertThat(context.getBean(Scheduler.class).isStarted()).isTrue());
    }

    @Test
    @DisplayName("수동 실행 JVM(spring.batch.job.enabled=true)에서는 스케줄이 켜져 있어도 시작하지 않는다")
    void staysInStandbyOnManualLaunchJvm() {
        contextRunner.withPropertyValues("batch.schedule.enabled=true", "spring.batch.job.enabled=true")
            .run(QuartzScheduleConfigConditionTest::assertScheduleOff);
    }

    @Test
    @DisplayName("스위치를 아예 주지 않으면 꺼진 것으로 본다")
    void defaultsToDisabled() {
        contextRunner.run(QuartzScheduleConfigConditionTest::assertScheduleOff);
    }

    @ParameterizedTest(name = "batch.schedule.enabled=\"{0}\"")
    @ValueSource(strings = {"false", "", "yes", "1"})
    @DisplayName("정확히 true 가 아니면 기동을 막지 않고 꺼진 것으로 떨어진다 — 빈 문자열 · 불리언이 아닌 값 포함")
    void treatsAnythingButTrueAsDisabledWithoutFailing(String value) {
        contextRunner.withPropertyValues("batch.schedule.enabled=" + value)
            .run(QuartzScheduleConfigConditionTest::assertScheduleOff);
    }

    private static void assertScheduleOff(AssertableApplicationContext context) throws Exception {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(SchedulerFactoryBeanCustomizer.class);
        assertThat(context).doesNotHaveBean(JobDetail.class).doesNotHaveBean(Trigger.class);
        assertThat(context.getBean(Scheduler.class).isStarted()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchScheduleProperties.class)
    static class BatchSchedulePropertiesTestConfig {
    }
}
