package com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.job;

import com.sneezecast.domainlayer.notifiableimport.adapter.in.batch.tasklet.NotifiableImportTasklet;
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
 * 질병관리청 전수신고 적재 잡 (공공데이터포털 전수신고 API → official_surveillance · official_source_snapshot, entity-design §3-2 · §4-1).
 *
 * <p>매주 화 05:00(KST) Quartz 트리거가 띄운다 ({@code QuartzScheduleConfig}, cron {@code batch.schedule.notifiable-cron}). 놓쳤거나 다시 받을 때는
 * 수동 실행한다 — {@code runAt} 은 현재 시각 같은 새 값으로 주고, {@code year} 를 주면 그 해와 전년을 받는다.
 * <pre>
 * java -jar batch-service.jar --spring.batch.job.enabled=true --spring.batch.job.name=notifiableImportJob \
 *     --spring.main.web-application-type=none runAt=2026-10-07T10:30:00 [year=2025]
 * </pre>
 * 같은 {@code runAt} 이 COMPLETED 면 거절되고, FAILED 면 같은 실행을 재시작한다 — 계획을 처음부터 다시 부르고 적재 이력의 {@code run_started_at}
 * 이 두 시도에 걸친다.
 * JobParameters 는 {@link NotifiableImportJobParametersValidator}. 요청 하나가 실패해도 나머지는 계속 받고, 실패가 있으면 끝에
 * {@code NOTIFIABLE_IMPORT_020}(RUN_FAILED)으로 Job 이 FAILED 다. 어느 요청이 왜 실패했는지는 {@code official_source_snapshot} 의 FAILED 행
 * ({@code request_key} · {@code error_code})에 있다.
 */
@Configuration
public class NotifiableImportJobConfig {

    public static final String JOB_NAME = "notifiableImportJob";
    private static final String STEP_NAME = "notifiableImportStep";

    @Bean
    public Job notifiableImportJob(JobRepository jobRepository, Step notifiableImportStep,
        NotifiableImportJobParametersValidator notifiableImportJobParametersValidator) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(notifiableImportJobParametersValidator)
            .start(notifiableImportStep)
            .build();
    }

    // 무자원 매니저를 쓰는 이유는 BatchServiceBeansConfig.taskletTransactionManager. DB 쓰기 트랜잭션은 OfficialIngestProcessor 가 요청마다 묶는다.
    @Bean
    public Step notifiableImportStep(
        JobRepository jobRepository,
        @Qualifier("taskletTransactionManager") PlatformTransactionManager taskletTransactionManager,
        NotifiableImportTasklet notifiableImportTasklet
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .tasklet(notifiableImportTasklet, taskletTransactionManager)
            .build();
    }
}
