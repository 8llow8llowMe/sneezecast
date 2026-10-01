package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sneezecast.domainlayer.schedule.application.command.ScheduledLaunchCommand;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleErrorCode;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleException;
import com.sneezecast.domainlayer.schedule.application.port.in.ScheduledJobLaunchUseCase;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** Quartz 진입점은 JobDataMap 을 명령으로 옮기고 예외를 Quartz 의 재시도 의미로 바꾸는 것만 한다. */
class SpringBatchLaunchQuartzJobTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-10-05T20:00:00Z");
    /** Quartz 는 fireTime 을 그 순간의 new Date() 로 찍는다 — 대기 루프가 일찍 깨면 예정 시각보다 이르다. */
    private static final Instant FIRED_AT = SCHEDULED_AT.minusMillis(1);

    private final ScheduledJobLaunchUseCase useCase = mock(ScheduledJobLaunchUseCase.class);
    private final SpringBatchLaunchQuartzJob quartzJob = new SpringBatchLaunchQuartzJob(useCase);

    @Test
    @DisplayName("JobDataMap 의 jobName · blockedBy(쉼표 구분, 공백 trim, 빈 값 제거)와 예정 · 실제 발화 시각을 명령으로 옮긴다")
    void convertsJobDataToCommand() throws Exception {
        quartzJob.executeInternal(context("notifiableImportJob", " notifiableImportJob, sentinelImportJob ,, ", Date.from(SCHEDULED_AT)));

        assertThat(capturedCommand()).isEqualTo(new ScheduledLaunchCommand(
            "notifiableImportJob", List.of("notifiableImportJob", "sentinelImportJob"), SCHEDULED_AT, FIRED_AT));
    }

    @Test
    @DisplayName("blockedBy 가 없으면 빈 목록이고, 예정 발화 시각이 없으면 실제 발화 시각으로 대신한다")
    void fallsBackWhenOptionalDataMissing() throws Exception {
        quartzJob.executeInternal(context("notifiableImportJob", null, null));

        ScheduledLaunchCommand command = capturedCommand();
        assertThat(command.mustNotBeRunning()).isEmpty();
        assertThat(command.scheduledAt()).isEqualTo(FIRED_AT);
    }

    @Test
    @DisplayName("ScheduleException 은 즉시 재시도하지 않는 JobExecutionException 으로 바꾼다 — 다시 쏴도 같은 결과다")
    void wrapsScheduleExceptionWithoutRefire() {
        ScheduleException failure = new ScheduleException(ScheduleErrorCode.JOB_NOT_FOUND, "notifiableImportJob");
        given(useCase.launch(any())).willThrow(failure);

        assertThatThrownBy(() -> quartzJob.executeInternal(context("notifiableImportJob", "notifiableImportJob", Date.from(SCHEDULED_AT))))
            .isInstanceOfSatisfying(JobExecutionException.class, exception -> {
                assertThat(exception.refireImmediately()).isFalse();
                assertThat(exception.getCause()).isSameAs(failure);
            });
    }

    @Test
    @DisplayName("예상 밖 RuntimeException(메타데이터 장애 등)도 즉시 재시도하지 않는 JobExecutionException 으로 바꾼다")
    void wrapsUnexpectedExceptionWithoutRefire() {
        IllegalStateException failure = new IllegalStateException("metadata unavailable");
        given(useCase.launch(any())).willThrow(failure);

        assertThatThrownBy(() -> quartzJob.executeInternal(context("notifiableImportJob", "notifiableImportJob", Date.from(SCHEDULED_AT))))
            .isInstanceOfSatisfying(JobExecutionException.class, exception -> {
                assertThat(exception.refireImmediately()).isFalse();
                assertThat(exception.getCause()).isSameAs(failure);
            });
    }

    @Test
    @DisplayName("jobName 이 없는 JobDetail 은 유스케이스를 부르지 않고 재시도 없이 실패한다")
    void rejectsMissingJobName() {
        assertThatThrownBy(() -> quartzJob.executeInternal(context(" ", "notifiableImportJob", Date.from(SCHEDULED_AT))))
            .isInstanceOfSatisfying(JobExecutionException.class, exception -> assertThat(exception.refireImmediately()).isFalse());
        verifyNoInteractions(useCase);
    }

    private ScheduledLaunchCommand capturedCommand() {
        ArgumentCaptor<ScheduledLaunchCommand> captor = ArgumentCaptor.forClass(ScheduledLaunchCommand.class);
        verify(useCase).launch(captor.capture());
        return captor.getValue();
    }

    private static JobExecutionContext context(String jobName, String blockedBy, Date scheduledFireTime) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(SpringBatchLaunchQuartzJob.JOB_NAME_KEY, jobName);
        if (blockedBy != null) {
            jobDataMap.put(SpringBatchLaunchQuartzJob.BLOCKED_BY_KEY, blockedBy);
        }
        JobExecutionContext context = mock(JobExecutionContext.class);
        given(context.getMergedJobDataMap()).willReturn(jobDataMap);
        given(context.getFireTime()).willReturn(Date.from(FIRED_AT));
        given(context.getScheduledFireTime()).willReturn(scheduledFireTime);
        given(context.getJobDetail()).willReturn(mock(JobDetail.class));
        return context;
    }
}
