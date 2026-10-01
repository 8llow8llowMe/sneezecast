package com.sneezecast.domainlayer.schedule.adapter.out.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sneezecast.domainlayer.schedule.application.exception.ScheduleErrorCode;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

/**
 * 스케줄이 부르는 이름은 빈 이름이 아니라 {@code Job.getName()} 이고, {@code runAt} 만 JobInstance 를 가른다. 둘이 어긋나면 "잡이 안 돈다"
 * 또는 "매번 이미 완료" 로 조용히 끝난다.
 */
class SpringBatchJobLaunchAdapterTest {

    private static final String JOB_NAME = "notifiableImportJob";
    private static final String RUN_AT = "2026-10-06T05:00:00";

    private final Job job = mock(Job.class);
    private final JobLauncher jobLauncher = mock(JobLauncher.class);

    private SpringBatchJobLaunchAdapter adapter() {
        given(job.getName()).willReturn(JOB_NAME);
        return new SpringBatchJobLaunchAdapter(List.of(job), jobLauncher);
    }

    @Test
    @DisplayName("잡 이름으로 Job 을 찾아 띄우고 JobExecution id 를 돌려준다")
    void launchesJobByName() throws Exception {
        SpringBatchJobLaunchAdapter adapter = adapter();
        given(jobLauncher.run(eq(job), any())).willReturn(executionWithId(77L));

        assertThat(adapter.launch(JOB_NAME, Map.of("runAt", RUN_AT), Map.of("trigger", "quartz"))).isEqualTo(77L);
    }

    @Test
    @DisplayName("runAt 만 identifying 이고 trigger 는 기록용이다")
    void marksOnlyRunAtAsIdentifying() throws Exception {
        SpringBatchJobLaunchAdapter adapter = adapter();
        given(jobLauncher.run(eq(job), any())).willReturn(executionWithId(1L));

        adapter.launch(JOB_NAME, Map.of("runAt", RUN_AT), Map.of("trigger", "quartz"));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(job), captor.capture());
        JobParameters parameters = captor.getValue();
        assertThat(parameters.getString("runAt")).isEqualTo(RUN_AT);
        assertThat(parameters.getParameter("runAt").isIdentifying()).isTrue();
        assertThat(parameters.getString("trigger")).isEqualTo("quartz");
        assertThat(parameters.getParameter("trigger").isIdentifying()).isFalse();
        assertThat(parameters.getIdentifyingParameters()).containsOnlyKeys("runAt");
    }

    @Test
    @DisplayName("컨텍스트에 없는 잡 이름이면 띄우지 않고 JOB_NOT_FOUND 로 실패한다")
    void failsForUnknownJobName() {
        SpringBatchJobLaunchAdapter adapter = adapter();

        assertThatThrownBy(() -> adapter.launch("unknownJob", Map.of("runAt", RUN_AT), Map.of()))
            .isInstanceOf(ScheduleException.class)
            .hasMessageContaining("unknownJob")
            .extracting(exception -> ((ScheduleException) exception).getErrorCode())
            .isEqualTo(ScheduleErrorCode.JOB_NOT_FOUND);
        verifyNoInteractions(jobLauncher);
    }

    @Test
    @DisplayName("같은 이름의 Job 빈이 둘이면 이름을 밝혀 DUPLICATE_JOB_NAME 으로 기동을 세운다")
    void failsWhenJobNamesCollide() {
        Job duplicate = mock(Job.class);
        given(job.getName()).willReturn(JOB_NAME);
        given(duplicate.getName()).willReturn(JOB_NAME);

        assertThatThrownBy(() -> new SpringBatchJobLaunchAdapter(List.of(job, duplicate), jobLauncher))
            .isInstanceOf(ScheduleException.class)
            .hasMessageContaining(JOB_NAME)
            .extracting(exception -> ((ScheduleException) exception).getErrorCode())
            .isEqualTo(ScheduleErrorCode.DUPLICATE_JOB_NAME);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("launcherRejections")
    @DisplayName("JobLauncher 가 실행을 거부하면 원인 예외를 품은 LAUNCH_FAILED 로 바꿔 던진다")
    void wrapsLauncherRejection(Exception rejection) throws Exception {
        SpringBatchJobLaunchAdapter adapter = adapter();
        given(jobLauncher.run(eq(job), any())).willThrow(rejection);

        assertThatThrownBy(() -> adapter.launch(JOB_NAME, Map.of("runAt", RUN_AT), Map.of()))
            .isInstanceOf(ScheduleException.class)
            .hasCause(rejection)
            .hasMessageContaining(rejection.getClass().getSimpleName())
            .extracting(exception -> ((ScheduleException) exception).getErrorCode())
            .isEqualTo(ScheduleErrorCode.LAUNCH_FAILED);
    }

    static Stream<Exception> launcherRejections() {
        return Stream.of(
            new JobExecutionAlreadyRunningException("already running"),
            new JobRestartException("restart refused"),
            new JobInstanceAlreadyCompleteException("already complete"),
            new JobParametersInvalidException("year missing"));
    }

    @Test
    @DisplayName("JobExecution id 가 없으면 LAUNCH_FAILED 로 실패한다")
    void failsWhenExecutionIdMissing() throws Exception {
        SpringBatchJobLaunchAdapter adapter = adapter();
        given(jobLauncher.run(eq(job), any())).willReturn(new JobExecution((Long) null));

        assertThatThrownBy(() -> adapter.launch(JOB_NAME, Map.of("runAt", RUN_AT), Map.of()))
            .isInstanceOf(ScheduleException.class)
            .extracting(exception -> ((ScheduleException) exception).getErrorCode())
            .isEqualTo(ScheduleErrorCode.LAUNCH_FAILED);
    }

    private static JobExecution executionWithId(long id) {
        return new JobExecution(id);
    }
}
