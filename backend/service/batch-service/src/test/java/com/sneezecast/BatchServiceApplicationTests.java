package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerMetaData;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.simpl.RAMJobStore;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * 기본 프로파일(dev)로 컨텍스트가 뜨고, Quartz · Spring Batch 가 배치 서비스의 전제대로 조립되는지 본다.
 *
 * <p>local 프로파일이 없으므로 dev 설정을 그대로 쓰고, 배포 때 env 로 들어오는 값만 여기서 채운다. 외부에는 붙지 않는다.
 * <ul>
 *   <li>MySQL → H2 인메모리 (드라이버 · URL 만 바꾼다). BATCH_* 메타 테이블은 H2 용 스크립트로 만든다.</li>
 *   <li>Eureka → 클라이언트를 끈다.</li>
 *   <li>스케줄 → 끈다. dev 기본값은 켜짐이지만, 적재 잡이 생긴 뒤에도 테스트 컨텍스트가 실제 적재를 시작하면 안 된다.
 *       켜졌을 때의 동작은 {@code QuartzScheduleConfigConditionTest} 가 본다.</li>
 *   <li>SGIS 인증키 → 주지 않는다. 키가 없어도 기동은 성공해야 한다 (잡 실행 때 실패).</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "BATCH_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "BATCH_DB_URL=jdbc:h2:mem:batch-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "BATCH_DB_USERNAME=sa",
    "BATCH_DB_PASSWORD=",
    "spring.batch.jdbc.initialize-schema=always",
    "BATCH_SCHEDULE_ENABLED=false"
})
@AutoConfigureMockMvc
class BatchServiceApplicationTests {

    private static final String SCHEDULER_NAME = "batch-service-scheduler";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("districtImportJob")
    private Job districtImportJob;

    @Test
    @DisplayName("Quartz 는 메모리 잡 스토어 · 스레드 1개다 — JDBC 스토어면 surveillance 스키마에 QRTZ_* 가 생기고, 2개 이상이면 적재 잡이 겹친다")
    void quartzUsesInMemoryStoreWithSingleThread() throws Exception {
        SchedulerMetaData metaData = scheduler.getMetaData();

        assertThat(metaData.getSchedulerName()).isEqualTo(SCHEDULER_NAME);
        assertThat(metaData.getJobStoreClass()).isEqualTo(RAMJobStore.class);
        assertThat(metaData.isJobStoreSupportsPersistence()).isFalse();
        assertThat(metaData.getThreadPoolSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Quartz 스레드는 데몬이다 — 아니면 수동 실행 JVM 이 잡을 끝내고도 종료되지 않는다")
    void quartzThreadsAreDaemons() {
        // 스케줄러는 시작 전(standby)에도 스레드풀과 스케줄러 스레드를 이미 띄워 둔다.
        List<Thread> quartzThreads = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(SCHEDULER_NAME))
            .toList();

        assertThat(quartzThreads).isNotEmpty();
        assertThat(quartzThreads).allSatisfy(thread -> assertThat(thread.isDaemon()).as(thread.getName()).isTrue());
    }

    @Test
    @DisplayName("스케줄 스위치가 꺼져 있으면 yml 의 auto-startup=false 가 그대로 남아 스케줄러가 standby 다")
    void schedulerStaysInStandbyWhenScheduleDisabled() throws Exception {
        assertThat(scheduler.isStarted()).isFalse();
        assertThat(scheduler.getJobKeys(GroupMatcher.anyGroup())).isEmpty();
    }

    @Test
    @DisplayName("Spring Batch 기반 빈은 있고, 기동 시 잡을 자동 실행하는 러너는 없다 (spring.batch.job.enabled=false)")
    void batchInfrastructureIsReadyWithoutLaunchingOnStartup() {
        assertThat(context.getBeansOfType(JobLauncher.class)).hasSize(1);
        assertThat(context.getBeansOfType(JobRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(JobExplorer.class)).hasSize(1);
        assertThat(context.getBeansOfType(JobLauncherApplicationRunner.class)).isEmpty();

        Integer executions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Integer.class);
        assertThat(executions).isZero();
    }

    @Test
    @DisplayName("트랜잭션 매니저는 둘이다 — primary 는 DataSource(JobRepository · 적재 쓰기), tasklet 스텝용은 무자원")
    void transactionManagersAreSplit() {
        assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(DataSourceTransactionManager.class);
        assertThat(context.getBean("transactionManager")).isInstanceOf(DataSourceTransactionManager.class);
        assertThat(context.getBean("taskletTransactionManager")).isInstanceOf(ResourcelessTransactionManager.class);
    }

    @Test
    @DisplayName("persistence-core 에서 Snowflake 생성기만 올라오고 JPA 는 없다 — EntityManagerFactory 빈도, JPA 클래스도 없다")
    void snowflakeWithoutJpa() {
        assertThat(context.getBeansOfType(SnowflakeIdGenerator.class)).hasSize(1);
        assertThat(context.containsBean("entityManagerFactory")).isFalse();
        // persistence-core 에서 starter-data-jpa 를 뺐으므로 클래스 자체가 클래스패스에 없어야 한다.
        assertThat(ClassUtils.isPresent("jakarta.persistence.EntityManagerFactory", getClass().getClassLoader())).isFalse();
        // 상주 JVM(spring.batch.job.enabled=false)이라 worker-id 는 기본 1 그대로다 — 비트 배치 [.. worker 5비트][sequence 12비트].
        long id = context.getBean(SnowflakeIdGenerator.class).generateId();
        assertThat((id >> 12) & 0x1F).isEqualTo(1L);
    }

    @Test
    @DisplayName("districtImportJob 은 SGIS 키 없이도 등록되고, year 없이 실행하면 JobInstance 를 만들기 전에 거절된다")
    void districtImportJobRejectsMissingYearBeforeCreatingInstance() {
        assertThatThrownBy(() -> jobLauncher.run(districtImportJob, new JobParameters()))
            .isInstanceOf(JobParametersInvalidException.class);

        Integer instances = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Integer.class);
        assertThat(instances).isZero();
    }

    @Test
    @DisplayName("initialize-schema=always 면 BATCH_* 메타 테이블이 만들어진다")
    void createsBatchMetadataTables() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'BATCH\\_%'", String.class);

        assertThat(tables).contains(
            "BATCH_JOB_INSTANCE",
            "BATCH_JOB_EXECUTION",
            "BATCH_JOB_EXECUTION_PARAMS",
            "BATCH_JOB_EXECUTION_CONTEXT",
            "BATCH_STEP_EXECUTION",
            "BATCH_STEP_EXECUTION_CONTEXT"
        );
    }

    @Test
    @DisplayName("상주 프로세스로 뜬다 — 서블릿 웹 컨텍스트라 웹 서버 스레드가 JVM 을 붙들고 actuator health 가 열린다")
    void runsAsResidentWebProcessWithActuator() throws Exception {
        assertThat(context).isInstanceOf(WebApplicationContext.class);

        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("공개 API 가 없으므로 API 문서 엔드포인트를 열지 않는다")
    void apiDocsAreDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isNotFound());
    }
}
