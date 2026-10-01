package com.sneezecast.domainlayer.schedule.application.service;

import com.sneezecast.domainlayer.schedule.application.command.ScheduledLaunchCommand;
import com.sneezecast.domainlayer.schedule.application.exception.ScheduleException;
import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult;
import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult.LaunchOutcome;
import com.sneezecast.domainlayer.schedule.application.port.in.ScheduledJobLaunchUseCase;
import com.sneezecast.domainlayer.schedule.application.port.out.BatchJobLaunchPort;
import com.sneezecast.domainlayer.schedule.application.port.out.ScheduleMetricsPort;
import com.sneezecast.domainlayer.schedule.application.service.processor.RunningJobGuardProcessor;
import com.sneezecast.global.properties.BatchScheduleProperties;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 스케줄 발화를 받아 배치 잡을 띄운다.
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> 이 파사드가 직접 쓰는 DB 는 없고 {@code JobLauncher} 는 메타데이터 트랜잭션을 JobRepository 안에서 스스로
 * 관리한다. 게다가 동기 launcher 라 잡이 끝날 때까지 반환하지 않으므로, 여기에 트랜잭션을 걸면 그 시간 내내 커넥션을 쥔다.
 *
 * <p><b>{@code runAt} 을 여기서 만든다.</b> 잡은 재실행 구분용 identifying 파라미터로 {@code runAt} 을 받는다. <b>예정 발화 시각</b>을 설정
 * 시간대(기본 Asia/Seoul)로 옮겨 초 단위로 자른 {@code 2026-10-06T05:00:00} 꼴이다 — 로그에서 바로 읽히고, 수동 실행이 손으로 넣는 값과 자릿수가
 * 같다. 실제 발화 시각은 Quartz 가 몇 ms 일찍 깨면 {@code 04:59:59} 가 되므로 쓰지 않는다. 겹침 판정 기준과 지표에는 실제 발화 시각을 쓴다.
 *
 * <p><b>같은 {@code runAt} 으로 두 번 발화하는 경우.</b> 구조상 오지 않는다 — misfire 정책이 {@code FireAndProceed} 라 놓친 발화는 예정 시각을
 * 지금으로 갱신해 한 번만 돈다. 그래도 온다면 Spring Batch 가 같은 JobInstance 로 보고 거절하므로 조용히 두 번 도는 대신 {@code LAUNCH_FAILED}
 * 로 FAILED 지표에 드러난다.
 *
 * <p>지표는 세 갈래(띄움 · 건너뜀 · 실패) 모두 남긴다. 겹침 조회나 실행 중 예상하지 못한 예외도 FAILED 로 센 뒤 다시 던진다 — 지표가 멈추면
 * "스케줄러가 죽었다" 와 구별되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledJobLaunchFacade implements ScheduledJobLaunchUseCase {

    /** {@code LocalDateTime.toString()} 은 초가 0 이면 초를 뺀다. 정각 발화가 대부분이라 고정 패턴을 쓴다. */
    private static final DateTimeFormatter RUN_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String RUN_AT_PARAMETER = "runAt";
    private static final String TRIGGER_PARAMETER = "trigger";
    private static final String TRIGGER_VALUE = "quartz";

    private final BatchScheduleProperties batchScheduleProperties;
    private final RunningJobGuardProcessor runningJobGuardProcessor;
    private final BatchJobLaunchPort batchJobLaunchPort;
    private final ScheduleMetricsPort scheduleMetricsPort;

    @Override
    public ScheduledLaunchResult launch(ScheduledLaunchCommand command) {
        String jobName = command.jobName();
        Instant firedAt = command.firedAt();
        String runAt = toRunAt(command.scheduledAt());

        try {
            Optional<String> blocking = runningJobGuardProcessor.findBlocking(
                command.mustNotBeRunning(), firedAt, batchScheduleProperties.staleRunningAfter());
            if (blocking.isPresent()) {
                log.warn("Schedule fire skipped. jobName={} runAt={} blockedBy={}", jobName, runAt, blocking.get());
                scheduleMetricsPort.recordFire(jobName, LaunchOutcome.SKIPPED_RUNNING, firedAt);
                return new ScheduledLaunchResult(jobName, runAt, LaunchOutcome.SKIPPED_RUNNING, null);
            }

            long executionId = batchJobLaunchPort.launch(
                jobName, Map.of(RUN_AT_PARAMETER, runAt), Map.of(TRIGGER_PARAMETER, TRIGGER_VALUE));
            log.info("Schedule fire launched. jobName={} runAt={} executionId={}", jobName, runAt, executionId);
            scheduleMetricsPort.recordFire(jobName, LaunchOutcome.LAUNCHED, firedAt);
            return new ScheduledLaunchResult(jobName, runAt, LaunchOutcome.LAUNCHED, executionId);
        } catch (ScheduleException exception) {
            log.error("Schedule fire failed. jobName={} runAt={} errorCode={}", jobName, runAt, exception.getErrorCode().getCode(), exception);
            scheduleMetricsPort.recordFire(jobName, LaunchOutcome.FAILED, firedAt);
            // 삼키지 않는다. Quartz 진입점이 JobExecutionException 으로 바꿔 트리거 이력에 남긴다.
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Schedule fire failed unexpectedly. jobName={} runAt={}", jobName, runAt, exception);
            scheduleMetricsPort.recordFire(jobName, LaunchOutcome.FAILED, firedAt);
            throw exception;
        }
    }

    /** 패턴에 밀리초 자리가 없어 초 단위로 잘린다. */
    private String toRunAt(Instant scheduledAt) {
        return RUN_AT_FORMAT.format(LocalDateTime.ofInstant(scheduledAt, batchScheduleProperties.zoneId()));
    }
}
