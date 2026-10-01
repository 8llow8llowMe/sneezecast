package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.global.properties.BatchScheduleProperties;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 헬퍼({@link QuartzScheduleConfig#newJobDetail} · {@link QuartzScheduleConfig#newCronTrigger})로 만든 JobDetail · Trigger 가 실제 메모리 스토어
 * 스케줄러에 어떻게 올라가는지 본다. 아직 스케줄할 잡이 없으므로 테스트 전용 설정({@link DummyScheduleTestConfig})이 더미 주간 잡을 등록한다 —
 * 주간 잡 이슈가 {@code QuartzScheduleConfig} 에 더할 빈 두 개와 같은 모양이다.
 *
 * <p><b>스케줄러는 시작하지 않는다.</b> 시작하면 더미 트리거가 실제 cron 시각에 발화하려 든다. {@code spring.quartz.auto-startup=false} 만으로는
 * 부족하다 — {@code QuartzScheduleConfig} 의 커스터마이저가 그것을 true 로 되돌린다. 그래서 <b>맨 뒤에 등록되는</b> 커스터마이저
 * ({@link NoAutoStartupTestConfig})로 다시 끈다. 그 눌림이 먹었는지는 {@code staysInStandbyWithSingleDaemonThread} 가 지킨다.
 *
 * <p>스케줄러 이름을 yml 과 다르게 둔다. Quartz 는 이름으로 JVM 전역 저장소에 스케줄러를 올리므로, 캐시된 {@code BatchServiceApplicationTests}
 * 컨텍스트의 {@code batch-service-scheduler} 가 살아 있으면 같은 이름으로 새 스케줄러를 만들 수 없다.
 */
class QuartzScheduleRegistrationTest {

    private static final String SCHEDULER_NAME = "schedule-registration-test-scheduler";
    private static final String DUMMY_JOB_NAME = "dummyWeeklyImportJob";
    private static final String OTHER_JOB_NAME = "dummyOtherImportJob";
    /** 매주 화요일 05:00 — entity-design §4-1 의 전수신고 주기와 같은 모양. */
    private static final String DUMMY_CRON = "0 0 5 ? * TUE";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, QuartzAutoConfiguration.class))
        .withUserConfiguration(
            BatchSchedulePropertiesTestConfig.class, QuartzScheduleConfig.class, DummyScheduleTestConfig.class, NoAutoStartupTestConfig.class)
        .withPropertyValues(
            "batch.schedule.enabled=true",
            "spring.quartz.job-store-type=memory",
            "spring.quartz.auto-startup=false",
            "spring.quartz.properties.org.quartz.scheduler.instanceName=" + SCHEDULER_NAME,
            "spring.quartz.properties.org.quartz.threadPool.threadCount=1",
            "spring.quartz.properties.org.quartz.threadPool.makeThreadsDaemons=true",
            "spring.quartz.properties.org.quartz.scheduler.makeSchedulerThreadDaemon=true");

    @Test
    @DisplayName("JobKey 는 잡 이름, TriggerKey 는 <잡 이름>Trigger 로 메모리 스토어에 등록되고, JobDetail 은 durable · 동시 실행 금지다")
    void registersJobDetailAndTriggerByJobName() {
        contextRunner.run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            JobDetail jobDetail = scheduler.getJobDetail(JobKey.jobKey(DUMMY_JOB_NAME));

            assertThat(jobDetail).isNotNull();
            assertThat(jobDetail.getJobClass()).isEqualTo(SpringBatchLaunchQuartzJob.class);
            assertThat(jobDetail.isDurable()).isTrue();
            // Quartz 2.3 API 이름의 오타(Exection)가 그대로다.
            assertThat(jobDetail.isConcurrentExectionDisallowed()).isTrue();

            Trigger trigger = scheduler.getTrigger(TriggerKey.triggerKey(DUMMY_JOB_NAME + "Trigger"));
            assertThat(trigger).isNotNull();
            assertThat(trigger.getJobKey()).isEqualTo(jobDetail.getKey());
        });
    }

    @Test
    @DisplayName("JobDataMap 이 실행할 잡 이름과 겹침 금지 목록을 실어 나른다 — 자기 자신이 맨 앞이고 중복 · 빈 값은 빠진다")
    void carriesJobDataWithSelfFirst() {
        contextRunner.run(context -> {
            var jobDataMap = context.getBean(Scheduler.class).getJobDetail(JobKey.jobKey(DUMMY_JOB_NAME)).getJobDataMap();

            assertThat(jobDataMap.getString(SpringBatchLaunchQuartzJob.JOB_NAME_KEY)).isEqualTo(DUMMY_JOB_NAME);
            assertThat(jobDataMap.getString(SpringBatchLaunchQuartzJob.BLOCKED_BY_KEY)).isEqualTo(DUMMY_JOB_NAME + "," + OTHER_JOB_NAME);
            assertThat(SpringBatchLaunchQuartzJob.parseBlockedBy(jobDataMap.getString(SpringBatchLaunchQuartzJob.BLOCKED_BY_KEY)))
                .containsExactly(DUMMY_JOB_NAME, OTHER_JOB_NAME);
        });
    }

    @Test
    @DisplayName("다음 발화는 JVM 시간대가 아니라 설정 시간대(Asia/Seoul) 기준 화요일 05:00 이고, misfire 는 FireAndProceed 다")
    void nextFireTimeFollowsConfiguredTimeZone() {
        contextRunner.run(context -> {
            CronTrigger trigger = (CronTrigger) context.getBean(Scheduler.class).getTrigger(TriggerKey.triggerKey(DUMMY_JOB_NAME + "Trigger"));

            assertThat(trigger.getTimeZone()).isEqualTo(TimeZone.getTimeZone("Asia/Seoul"));
            ZonedDateTime nextFire = trigger.getNextFireTime().toInstant().atZone(ZoneId.of("Asia/Seoul"));
            assertThat(nextFire.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
            assertThat(nextFire.getHour()).isEqualTo(5);
            assertThat(nextFire.getMinute()).isZero();
            assertThat(trigger.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW);
        });
    }

    @Test
    @DisplayName("batch.schedule.time-zone 을 바꾸면 같은 cron 이 그 시간대의 05:00 이 된다 — 시간대는 설정이 정한다")
    void timeZoneComesFromProperties() {
        contextRunner.withPropertyValues("batch.schedule.time-zone=UTC").run(context -> {
            CronTrigger trigger = (CronTrigger) context.getBean(Scheduler.class).getTrigger(TriggerKey.triggerKey(DUMMY_JOB_NAME + "Trigger"));

            assertThat(trigger.getTimeZone()).isEqualTo(TimeZone.getTimeZone("UTC"));
            assertThat(trigger.getNextFireTime().toInstant().atZone(ZoneId.of("UTC")).getHour()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("스케줄러는 standby 로 남고, 스레드는 1개이며 이미 떠 있는 Quartz 스레드는 전부 데몬이다")
    void staysInStandbyWithSingleDaemonThread() {
        contextRunner.run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.isStarted()).isFalse();
            assertThat(scheduler.getSchedulerName()).isEqualTo(SCHEDULER_NAME);
            assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(1);

            // 스레드풀과 스케줄러 스레드는 start() 가 아니라 스케줄러 생성 시점에 만들어진다.
            List<Thread> quartzThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(SCHEDULER_NAME))
                .toList();
            assertThat(quartzThreads).isNotEmpty();
            assertThat(quartzThreads).allSatisfy(thread -> assertThat(thread.isDaemon()).as(thread.getName()).isTrue());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchScheduleProperties.class)
    static class BatchSchedulePropertiesTestConfig {
    }

    /** 주간 잡 이슈가 {@code QuartzScheduleConfig} 에 더할 빈 두 개와 같은 모양의 더미. 중복 · 빈 값이 걸러지는지도 함께 본다. */
    @Configuration(proxyBeanMethods = false)
    static class DummyScheduleTestConfig {

        @Bean
        JobDetail dummyWeeklyImportJobDetail() {
            return QuartzScheduleConfig.newJobDetail(DUMMY_JOB_NAME, OTHER_JOB_NAME, " ", DUMMY_JOB_NAME);
        }

        @Bean
        Trigger dummyWeeklyImportTrigger(
            BatchScheduleProperties batchScheduleProperties,
            @Qualifier("dummyWeeklyImportJobDetail") JobDetail dummyWeeklyImportJobDetail
        ) {
            return QuartzScheduleConfig.newCronTrigger(dummyWeeklyImportJobDetail, DUMMY_CRON, batchScheduleProperties);
        }
    }

    /**
     * {@code QuartzScheduleConfig} 뒤에 등록돼 auto-startup 을 다시 끈다. 커스터마이저는 등록 순서대로 적용되므로(둘 다 기본 순서) 나중에
     * 등록된 이쪽이 최종 값을 정한다.
     */
    @Configuration(proxyBeanMethods = false)
    static class NoAutoStartupTestConfig {

        @Bean
        SchedulerFactoryBeanCustomizer testNoAutoStartupCustomizer() {
            return factory -> factory.setAutoStartup(false);
        }
    }
}
