package com.sneezecast.domainlayer.schedule.adapter.out.batch;

import com.sneezecast.domainlayer.schedule.application.exception.ScheduleErrorCode;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleException;
import com.sneezecast.domainlayer.schedule.application.port.out.BatchJobLaunchPort;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Component;

/**
 * 이름으로 {@code Job} 빈을 찾아 {@code JobLauncher} 로 띄운다.
 *
 * <p><b>{@code JobLauncher} 를 쓴다.</b> Spring Batch 5.2(Boot 3.4.5)에서 {@code JobLauncher} 는 현행 API 이고, 부트 자동 구성이 동기
 * {@code TaskExecutorJobLauncher} 하나를 둔다. {@code JobOperator.start} 는 파라미터를 문자열 하나로 받아 identifying 여부를 문자열 규칙으로
 * 적어야 하고 {@code JobRegistry} 등록이 전제라 쓰지 않는다. 동기라서 잡이 끝날 때까지 Quartz 스레드(1개)를 쥐는데, 그것이 "적재 잡끼리 겹치지
 * 않는다" 를 보장하는 방식이다.
 *
 * <p>{@code JobRegistry} 대신 컨텍스트의 {@code Job} 빈 전부를 받아 {@code Job.getName()} 으로 색인한다. 레지스트리 등록 시점에 기대면 "왜 이
 * 잡만 없지" 가 기동 순서 문제로 번진다. 빈 이름이 아니라 잡 이름을 쓰는 것은 스케줄이 부르는 이름이 {@code --spring.batch.job.name} 과 같아야
 * 하기 때문이다.
 */
@Component
public class SpringBatchJobLaunchAdapter implements BatchJobLaunchPort {

    private final Map<String, Job> jobsByName;
    private final JobLauncher jobLauncher;

    public SpringBatchJobLaunchAdapter(List<Job> jobs, JobLauncher jobLauncher) {
        this.jobsByName = indexByName(jobs);
        this.jobLauncher = jobLauncher;
    }

    /**
     * 이름이 겹치면 기동을 세운다. 잡 이름은 스케줄과 수동 실행이 함께 쓰는 식별자라, 겹치면 어느 잡이 도는지가 빈 배선 순서에 달린다. 조용히
     * 하나를 고르는 대신 이름을 밝혀 세운다 (병합 함수가 없으면 {@code Duplicate key} 메시지에 {@code Job.toString()} 만 실린다).
     */
    private static Map<String, Job> indexByName(List<Job> jobs) {
        return jobs.stream().collect(Collectors.toUnmodifiableMap(
            Job::getName,
            Function.identity(),
            (first, second) -> {
                throw new ScheduleException(ScheduleErrorCode.DUPLICATE_JOB_NAME, first.getName());
            }));
    }

    @Override
    public long launch(String jobName, Map<String, String> identifyingParameters, Map<String, String> nonIdentifyingParameters) {
        Job job = jobsByName.get(jobName);
        if (job == null) {
            throw new ScheduleException(ScheduleErrorCode.JOB_NOT_FOUND, jobName);
        }

        JobParametersBuilder parametersBuilder = new JobParametersBuilder();
        identifyingParameters.forEach((key, value) -> parametersBuilder.addString(key, value, true));
        nonIdentifyingParameters.forEach((key, value) -> parametersBuilder.addString(key, value, false));

        JobExecution execution;
        try {
            execution = jobLauncher.run(job, parametersBuilder.toJobParameters());
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                 | JobInstanceAlreadyCompleteException | JobParametersInvalidException exception) {
            throw new ScheduleException(ScheduleErrorCode.LAUNCH_FAILED, exception, jobName, exception.getClass().getSimpleName());
        }

        // JobRepository 가 실행 행을 만든 뒤에만 반환되므로 id 가 없으면 메타데이터 저장이 어긋난 것이다.
        if (execution == null || execution.getId() == null) {
            throw new ScheduleException(ScheduleErrorCode.LAUNCH_FAILED, jobName, "executionIdMissing");
        }
        return execution.getId();
    }
}
