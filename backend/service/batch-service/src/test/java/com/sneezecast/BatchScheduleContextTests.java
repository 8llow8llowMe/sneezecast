package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.districtimport.adapter.in.batch.job.DistrictImportJobConfig;
import com.sneezecast.domainlayer.schedule.adapter.in.scheduler.SpringBatchLaunchQuartzJob;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleErrorCode;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.KeyMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 스케줄을 켠 전체 컨텍스트 게이트. {@code BatchServiceApplicationTests} 와 같은 대체값에 {@code BATCH_SCHEDULE_ENABLED=true} 만 다르다.
 *
 * <p>여기서는 스케줄러가 <b>실제로 시작된다</b>. 등록된 트리거가 없어 저절로 발화하는 것은 없다. 대신 테스트가 {@code districtImportJob} 을 향한
 * 1회성 트리거를 직접 걸어, Quartz 가 만든 {@link SpringBatchLaunchQuartzJob} 에 유스케이스가 주입되고 {@code JobLauncher} 까지 닿는지 본다.
 * {@code year} 없이 띄우므로 잡 검증기가 JobInstance 를 만들기 전에 거절한다 — 외부 호출도 메타 행도 생기지 않는다.
 *
 * <p>스케줄러 이름 · H2 DB 를 {@code BatchServiceApplicationTests} 와 다르게 둔다. 두 컨텍스트가 테스트 캐시에 함께 살아 있을 수 있고, Quartz 는
 * 같은 이름의 스케줄러를 두 번 만들지 못한다. 시작된 스케줄러가 다른 테스트까지 남지 않게 컨텍스트를 닫는다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "BATCH_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "BATCH_DB_URL=jdbc:h2:mem:batch-schedule-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "BATCH_DB_USERNAME=sa",
    "BATCH_DB_PASSWORD=",
    "spring.batch.jdbc.initialize-schema=always",
    "BATCH_SCHEDULE_ENABLED=true",
    "spring.quartz.properties.org.quartz.scheduler.instanceName=batch-schedule-context-scheduler"
})
@DirtiesContext
class BatchScheduleContextTests {

    private static final String JOB_NAME = DistrictImportJobConfig.JOB_NAME;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("스케줄을 켜면 커스터마이저가 auto-startup=false 를 이겨 스케줄러가 시작되고, 스케줄할 잡이 없어 등록된 JobDetail 은 없다")
    void startsEmptySchedulerWhenEnabled() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.getJobKeys(GroupMatcher.anyGroup())).isEmpty();
    }

    @Test
    @DisplayName("Quartz 발화가 주입된 유스케이스를 거쳐 JobLauncher 까지 닿고, 실행 거절은 재시도 없는 실패 + FAILED 지표로 남는다")
    void bridgesQuartzFireToJobLauncher() throws Exception {
        JobKey jobKey = JobKey.jobKey("bridgeProbe");
        JobDetail jobDetail = JobBuilder.newJob(SpringBatchLaunchQuartzJob.class)
            .withIdentity(jobKey)
            .usingJobData(SpringBatchLaunchQuartzJob.JOB_NAME_KEY, JOB_NAME)
            .usingJobData(SpringBatchLaunchQuartzJob.BLOCKED_BY_KEY, JOB_NAME)
            .build();
        CompletableFuture<JobExecutionException> outcome = new CompletableFuture<>();
        scheduler.getListenerManager().addJobListener(new CompletionListener(outcome), KeyMatcher.keyEquals(jobKey));

        try {
            scheduler.scheduleJob(jobDetail, TriggerBuilder.newTrigger().forJob(jobKey).startNow().build());
            JobExecutionException failure = outcome.get(30, TimeUnit.SECONDS);

            assertThat(failure).isNotNull();
            assertThat(failure.refireImmediately()).isFalse();
            assertThat(failure.getCause()).isInstanceOfSatisfying(ScheduleException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ScheduleErrorCode.LAUNCH_FAILED));
        } finally {
            scheduler.getListenerManager().removeJobListener(CompletionListener.NAME);
            scheduler.deleteJob(jobKey);
        }

        Counter failed = meterRegistry.find("batch.schedule.fire").tags("job", JOB_NAME, "result", "failed").counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Integer.class)).isZero();
    }

    /** 잡이 끝나면 Quartz 가 넘긴 예외(성공이면 null)를 꺼내 준다. */
    private record CompletionListener(CompletableFuture<JobExecutionException> outcome) implements JobListener {

        static final String NAME = "bridgeProbeListener";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public void jobToBeExecuted(JobExecutionContext context) {
        }

        @Override
        public void jobExecutionVetoed(JobExecutionContext context) {
            outcome.completeExceptionally(new IllegalStateException("vetoed"));
        }

        @Override
        public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
            outcome.complete(jobException);
        }
    }
}
