package com.sneezecast.domainlayer.sentinelimport.adapter.in.batch.job;

import com.sneezecast.domainlayer.sentinelimport.adapter.in.batch.tasklet.SentinelImportTasklet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 감염병포털 표본감시 적재 잡 (포털 화면 데이터 → official_surveillance · official_source_snapshot, entity-design §3-2 · §4-1).
 *
 * <p>매주 금 06:00(KST) Quartz 트리거가 띄운다 ({@code QuartzScheduleConfig}, cron {@code batch.schedule.sentinel-cron}). 놓쳤거나 다시 받을 때는
 * 수동 실행한다 — {@code runAt} 은 현재 시각 같은 새 값으로 주고, 최근 N주보다 오래 빠졌으면 {@code baseDate} 로 기준일을 옮긴다.
 * <pre>
 * java -jar batch-service.jar --spring.batch.job.enabled=true --spring.batch.job.name=sentinelImportJob \
 *     --spring.main.web-application-type=none runAt=2026-10-09T10:30:00 [baseDate=2026-08-14]
 * </pre>
 * 같은 {@code runAt} 이 COMPLETED 면 거절되고, FAILED 면 같은 실행을 재시작한다 — 계획을 처음부터 다시 부르고 적재 이력의 {@code run_started_at}
 * 이 두 시도에 걸친다.
 * JobParameters 는 {@link SentinelImportJobParametersValidator}. 요청 하나가 실패해도 나머지는 계속 받고, 실패가 있으면 끝에
 * {@code SENTINEL_IMPORT_020}(RUN_FAILED)으로 Job 이 FAILED 다. 어느 요청이 왜 실패했는지는 {@code official_source_snapshot} 의 FAILED 행
 * ({@code request_key} · {@code error_code})에 있다.
 */
@Configuration
public class SentinelImportJobConfig {

    public static final String JOB_NAME = "sentinelImportJob";
    private static final String STEP_NAME = "sentinelImportStep";

    @Bean
    public Job sentinelImportJob(JobRepository jobRepository, Step sentinelImportStep,
        SentinelImportJobParametersValidator sentinelImportJobParametersValidator) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(sentinelImportJobParametersValidator)
            .start(sentinelImportStep)
            .build();
    }

    // 무자원 매니저를 쓰는 이유는 BatchServiceBeansConfig.taskletTransactionManager. DB 쓰기 트랜잭션은 OfficialIngestProcessor 가 요청마다 묶는다.
    @Bean
    public Step sentinelImportStep(
        JobRepository jobRepository,
        @Qualifier("taskletTransactionManager") PlatformTransactionManager taskletTransactionManager,
        SentinelImportTasklet sentinelImportTasklet
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .tasklet(sentinelImportTasklet, taskletTransactionManager)
            .build();
    }
}
