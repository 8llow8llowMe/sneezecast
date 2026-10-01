package com.sneezecast.domainlayer.schedule.adapter.out.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sneezecast.domainlayer.schedule.application.port.out.query.RunningJobExecutionQueryResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class JobExplorerBatchJobExecutionAdapterTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 6, 4, 59, 58);
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 10, 6, 5, 0, 0);

    private final JobExplorer jobExplorer = mock(JobExplorer.class);
    private final JobExplorerBatchJobExecutionAdapter adapter = new JobExplorerBatchJobExecutionAdapter(jobExplorer);

    @Test
    @DisplayName("잡 이름마다 실행 중인 실행을 모으고, 메타 시각(LocalDateTime)은 그것을 쓴 JVM 의 기본 시간대로 Instant 로 바꾼다")
    void collectsRunningExecutionsPerJobName() {
        given(jobExplorer.findRunningJobExecutions("notifiableImportJob")).willReturn(Set.of(execution(11L, CREATED, STARTED)));
        given(jobExplorer.findRunningJobExecutions("sentinelImportJob")).willReturn(Set.of());

        List<RunningJobExecutionQueryResult> running = adapter.findRunning(List.of("notifiableImportJob", "sentinelImportJob"));

        assertThat(running).containsExactly(
            new RunningJobExecutionQueryResult("notifiableImportJob", 11L, STARTED.atZone(ZoneId.systemDefault()).toInstant()));
    }

    @Test
    @DisplayName("아직 시작 시각이 없는 STARTING 실행은 생성 시각으로 채운다")
    void fallsBackToCreateTimeWhenNotStarted() {
        given(jobExplorer.findRunningJobExecutions("notifiableImportJob")).willReturn(Set.of(execution(12L, CREATED, null)));

        List<RunningJobExecutionQueryResult> running = adapter.findRunning(List.of("notifiableImportJob"));

        assertThat(running).extracting(RunningJobExecutionQueryResult::startedAt)
            .containsExactly(CREATED.atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("실제 BATCH_* 메타 테이블(H2)에서 실행 중(STARTED)인 실행만 잡 이름 · 시작 시각과 함께 돌려주고 COMPLETED 는 뺀다")
    void readsRunningExecutionsFromRealMetadata() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:schedule-explorer-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-h2.sql")).execute(dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

        JobRepositoryFactoryBean repositoryFactory = new JobRepositoryFactoryBean();
        repositoryFactory.setDataSource(dataSource);
        repositoryFactory.setTransactionManager(transactionManager);
        repositoryFactory.afterPropertiesSet();
        JobRepository jobRepository = repositoryFactory.getObject();

        JobExplorerFactoryBean explorerFactory = new JobExplorerFactoryBean();
        explorerFactory.setDataSource(dataSource);
        explorerFactory.setTransactionManager(transactionManager);
        explorerFactory.afterPropertiesSet();
        JobExplorerBatchJobExecutionAdapter realAdapter = new JobExplorerBatchJobExecutionAdapter(explorerFactory.getObject());

        JobExecution running = jobRepository.createJobExecution("notifiableImportJob",
            new JobParametersBuilder().addString("runAt", "2026-10-06T05:00:00").toJobParameters());
        running.setStatus(BatchStatus.STARTED);
        running.setStartTime(STARTED);
        jobRepository.update(running);

        JobExecution completed = jobRepository.createJobExecution("notifiableImportJob",
            new JobParametersBuilder().addString("runAt", "2026-09-29T05:00:00").toJobParameters());
        completed.setStatus(BatchStatus.COMPLETED);
        completed.setStartTime(STARTED.minusWeeks(1));
        completed.setEndTime(STARTED.minusWeeks(1).plusMinutes(3));
        jobRepository.update(completed);

        List<RunningJobExecutionQueryResult> found = realAdapter.findRunning(List.of("notifiableImportJob", "sentinelImportJob"));

        assertThat(found).containsExactly(new RunningJobExecutionQueryResult(
            "notifiableImportJob", running.getId(), STARTED.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private static JobExecution execution(long id, LocalDateTime createTime, LocalDateTime startTime) {
        JobExecution execution = new JobExecution(id);
        execution.setCreateTime(createTime);
        execution.setStartTime(startTime);
        return execution;
    }
}
