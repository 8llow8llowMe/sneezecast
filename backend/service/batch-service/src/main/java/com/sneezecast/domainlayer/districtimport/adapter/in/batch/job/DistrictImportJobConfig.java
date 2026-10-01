package com.sneezecast.domainlayer.districtimport.adapter.in.batch.job;

import com.sneezecast.domainlayer.districtimport.adapter.in.batch.tasklet.DistrictImportTasklet;
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
 * 행정동 마스터 적재 잡 (SGIS → district, entity-design §3-1 · §4-1).
 *
 * <p><b>수동 실행 전용</b>이다 — SGIS 기준 연도는 1년에 한 번 공개되고 언제 나오는지 정해져 있지 않다. Quartz 트리거를 두지 않는다.
 * <pre>
 * java -jar batch-service.jar --spring.batch.job.enabled=true --spring.batch.job.name=districtImportJob \
 *     --spring.main.web-application-type=none year=2025 runAt=2026-10-01T10:00:00
 * </pre>
 * JobParameters 는 {@link DistrictImportJobParametersValidator}. 사라지는 코드 비율이 임계값을 넘어 {@code MASS_RETIRE_BLOCKED} 로 실패했다면,
 * 원인을 확인한 뒤 {@code allowMassRetire=true} 와 새 {@code runAt} 으로 다시 돌린다.
 */
@Configuration
public class DistrictImportJobConfig {

    public static final String JOB_NAME = "districtImportJob";
    private static final String STEP_NAME = "districtImportStep";

    @Bean
    public Job districtImportJob(JobRepository jobRepository, Step districtImportStep, DistrictImportJobParametersValidator districtImportJobParametersValidator) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(districtImportJobParametersValidator)
            .start(districtImportStep)
            .build();
    }

    // 무자원 매니저를 쓰는 이유는 BatchServiceBeansConfig.taskletTransactionManager. DB 쓰기 트랜잭션은 DistrictImportFacade 가 따로 묶는다.
    @Bean
    public Step districtImportStep(
        JobRepository jobRepository,
        @Qualifier("taskletTransactionManager") PlatformTransactionManager taskletTransactionManager,
        DistrictImportTasklet districtImportTasklet
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .tasklet(districtImportTasklet, taskletTransactionManager)
            .build();
    }
}
