package com.sneezecast.domainlayer.schedule.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sneezecast.domainlayer.schedule.application.command.ScheduledLaunchCommand;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleErrorCode;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleException;
import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult;
import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult.LaunchOutcome;
import com.sneezecast.domainlayer.schedule.application.port.out.BatchJobLaunchPort;
import com.sneezecast.domainlayer.schedule.application.port.out.ScheduleMetricsPort;
import com.sneezecast.domainlayer.schedule.application.service.processor.RunningJobGuardProcessor;
import com.sneezecast.global.properties.BatchScheduleProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 발화 한 번이 갈 수 있는 길은 셋이다 — 띄운다 / 겹쳐서 넘긴다 / 띄우다 실패한다. 셋 다 지표에 남아야 "스케줄러가 살아 있나" 를 밖에서 본다.
 */
class ScheduledJobLaunchFacadeTest {

    private static final String JOB_NAME = "notifiableImportJob";
    private static final List<String> BLOCKED_BY = List.of("notifiableImportJob", "sentinelImportJob");
    private static final Duration STALE_AFTER = Duration.ofHours(6);

    /** 예정 발화 2026-10-06(화) 05:00 KST. UTC 로는 전날이라 시간대를 틀리면 날짜부터 어긋난다. 정각이라 초 자리가 빠지지 않는지도 본다. */
    private static final Instant SCHEDULED_AT = Instant.parse("2026-10-05T20:00:00Z");
    /** Quartz 대기 루프가 1ms 일찍 깨어난 실제 발화 시각 (04:59:59.999 KST). */
    private static final Instant FIRED_AT = SCHEDULED_AT.minusMillis(1);
    private static final String EXPECTED_RUN_AT = "2026-10-06T05:00:00";

    private final RunningJobGuardProcessor runningJobGuardProcessor = mock(RunningJobGuardProcessor.class);
    private final BatchJobLaunchPort batchJobLaunchPort = mock(BatchJobLaunchPort.class);
    private final ScheduleMetricsPort scheduleMetricsPort = mock(ScheduleMetricsPort.class);

    private final ScheduledJobLaunchFacade facade = new ScheduledJobLaunchFacade(
        new BatchScheduleProperties("Asia/Seoul", STALE_AFTER, null, null), runningJobGuardProcessor, batchJobLaunchPort, scheduleMetricsPort);

    private final ScheduledLaunchCommand command = new ScheduledLaunchCommand(JOB_NAME, BLOCKED_BY, SCHEDULED_AT, FIRED_AT);

    @Test
    @DisplayName("1ms 일찍 발화해도 runAt 은 예정 시각(KST, 초 단위) 05:00:00 이고, 겹침 판정 · 지표는 실제 발화 시각을 쓴다")
    void launchesWithSeoulRunAt() {
        given(runningJobGuardProcessor.findBlocking(BLOCKED_BY, FIRED_AT, STALE_AFTER)).willReturn(Optional.empty());
        given(batchJobLaunchPort.launch(JOB_NAME, Map.of("runAt", EXPECTED_RUN_AT), Map.of("trigger", "quartz"))).willReturn(4321L);

        ScheduledLaunchResult result = facade.launch(command);

        assertThat(result).isEqualTo(new ScheduledLaunchResult(JOB_NAME, EXPECTED_RUN_AT, LaunchOutcome.LAUNCHED, 4321L));
        verify(scheduleMetricsPort).recordFire(JOB_NAME, LaunchOutcome.LAUNCHED, FIRED_AT);
    }

    @Test
    @DisplayName("겹치는 잡이 돌고 있으면 띄우지 않고 SKIPPED_RUNNING 을 지표에 남긴 뒤 정상 반환한다")
    void skipsWhenBlockingJobIsRunning() {
        given(runningJobGuardProcessor.findBlocking(BLOCKED_BY, FIRED_AT, STALE_AFTER)).willReturn(Optional.of("sentinelImportJob"));

        ScheduledLaunchResult result = facade.launch(command);

        assertThat(result).isEqualTo(new ScheduledLaunchResult(JOB_NAME, EXPECTED_RUN_AT, LaunchOutcome.SKIPPED_RUNNING, null));
        verify(batchJobLaunchPort, never()).launch(anyString(), any(), any());
        verify(scheduleMetricsPort).recordFire(JOB_NAME, LaunchOutcome.SKIPPED_RUNNING, FIRED_AT);
    }

    @Test
    @DisplayName("실행이 거부되면 FAILED 를 지표에 남기고 ScheduleException 을 그대로 다시 던진다")
    void recordsFailureAndRethrows() {
        given(runningJobGuardProcessor.findBlocking(BLOCKED_BY, FIRED_AT, STALE_AFTER)).willReturn(Optional.empty());
        given(batchJobLaunchPort.launch(eq(JOB_NAME), any(), any()))
            .willThrow(new ScheduleException(ScheduleErrorCode.LAUNCH_FAILED, JOB_NAME, "JobInstanceAlreadyCompleteException"));

        assertThatThrownBy(() -> facade.launch(command))
            .isInstanceOf(ScheduleException.class)
            .extracting(exception -> ((ScheduleException) exception).getErrorCode())
            .isEqualTo(ScheduleErrorCode.LAUNCH_FAILED);
        verify(scheduleMetricsPort).recordFire(JOB_NAME, LaunchOutcome.FAILED, FIRED_AT);
    }

    @Test
    @DisplayName("겹침 조회(메타데이터 DB)가 예상 밖으로 실패해도 FAILED 를 남기고 다시 던진다 — 지표가 멈추면 스케줄러 사망과 구별되지 않는다")
    void recordsUnexpectedFailureAndRethrows() {
        IllegalStateException metadataDown = new IllegalStateException("metadata unavailable");
        given(runningJobGuardProcessor.findBlocking(BLOCKED_BY, FIRED_AT, STALE_AFTER)).willThrow(metadataDown);

        assertThatThrownBy(() -> facade.launch(command)).isSameAs(metadataDown);
        verify(batchJobLaunchPort, never()).launch(anyString(), any(), any());
        verify(scheduleMetricsPort).recordFire(JOB_NAME, LaunchOutcome.FAILED, FIRED_AT);
    }

    @Test
    @DisplayName("runAt 은 JVM 이 아니라 설정 시간대로 만든다")
    void formatsRunAtInConfiguredTimeZone() {
        ScheduledJobLaunchFacade utcFacade = new ScheduledJobLaunchFacade(
            new BatchScheduleProperties("UTC", STALE_AFTER, null, null), runningJobGuardProcessor, batchJobLaunchPort, scheduleMetricsPort);
        given(runningJobGuardProcessor.findBlocking(BLOCKED_BY, FIRED_AT, STALE_AFTER)).willReturn(Optional.of("sentinelImportJob"));

        assertThat(utcFacade.launch(command).runAt()).isEqualTo("2026-10-05T20:00:00");
    }

    @Test
    @DisplayName("예정 시각에 밀리초가 붙어도 runAt 은 초 단위로 잘린다")
    void truncatesRunAtToSeconds() {
        Instant scheduledWithMillis = SCHEDULED_AT.plusMillis(789);
        given(runningJobGuardProcessor.findBlocking(BLOCKED_BY, FIRED_AT, STALE_AFTER)).willReturn(Optional.empty());
        given(batchJobLaunchPort.launch(JOB_NAME, Map.of("runAt", EXPECTED_RUN_AT), Map.of("trigger", "quartz"))).willReturn(1L);

        ScheduledLaunchResult result = facade.launch(new ScheduledLaunchCommand(JOB_NAME, BLOCKED_BY, scheduledWithMillis, FIRED_AT));

        assertThat(result.runAt()).isEqualTo(EXPECTED_RUN_AT);
    }
}
