package com.sneezecast.domainlayer.schedule.adapter.in.scheduler;

import com.sneezecast.domainlayer.schedule.application.command.ScheduledLaunchCommand;
import com.sneezecast.domainlayer.schedule.application.port.in.ScheduledJobLaunchUseCase;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 발화를 유스케이스 호출로 옮기는 얇은 진입점. {@code JobDetail} 은 {@link QuartzScheduleConfig#newJobDetail} 로만 만든다.
 *
 * <p><b>생성자 주입이 된다.</b> 부트 {@code QuartzAutoConfiguration} 이 거는 {@code SpringBeanJobFactory} 는 잡 인스턴스를
 * {@code AutowireCapableBeanFactory.createBean(jobClass)} 로 만들므로(spring-context-support 6.2) 단일 생성자가 그대로 주입된다.
 * 실제 Quartz 생성 경로는 {@code BatchScheduleContextTests} 가 검증한다.
 *
 * <p>{@code @DisallowConcurrentExecution} 은 같은 JobKey 의 중복 발화만 막는다. 다른 잡끼리 · 수동 실행 JVM 과의 겹침은
 * {@code RunningJobGuardProcessor} 가 배치 메타데이터로 본다.
 */
@DisallowConcurrentExecution
@RequiredArgsConstructor
public class SpringBatchLaunchQuartzJob extends QuartzJobBean {

    /** 실행할 배치 잡 이름. */
    public static final String JOB_NAME_KEY = "jobName";

    /** 돌고 있으면 이번 발화를 건너뛸 잡 이름들. 쉼표로 구분한다. */
    public static final String BLOCKED_BY_KEY = "blockedBy";

    static final String BLOCKED_BY_SEPARATOR = ",";

    private final ScheduledJobLaunchUseCase scheduledJobLaunchUseCase;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        JobDataMap jobDataMap = context.getMergedJobDataMap();
        String jobName = jobDataMap.getString(JOB_NAME_KEY);
        if (jobName == null || jobName.isBlank()) {
            // JobDetail 을 헬퍼 밖에서 만든 배선 오류다. 다시 쏴도 같으므로 즉시 재시도하지 않는다.
            throw new JobExecutionException("JobDataMap has no " + JOB_NAME_KEY + ". jobKey=" + context.getJobDetail().getKey(), false);
        }
        List<String> blockedBy = parseBlockedBy(jobDataMap.getString(BLOCKED_BY_KEY));

        // runAt 은 예정 시각에서 만든다. 실제 발화 시각(getFireTime)은 그 순간의 new Date() 라 Quartz 대기 루프가 몇 ms 일찍 깨면
        // 04:59:59 로 찍혀 cron 시각과 어긋난다. 예정 시각이 없으면 실제 발화 시각으로 대신한다.
        Instant firedAt = context.getFireTime().toInstant();
        Date scheduledFireTime = context.getScheduledFireTime();
        Instant scheduledAt = scheduledFireTime != null ? scheduledFireTime.toInstant() : firedAt;

        try {
            scheduledJobLaunchUseCase.launch(new ScheduledLaunchCommand(jobName, blockedBy, scheduledAt, firedAt));
        } catch (RuntimeException exception) {
            // refireImmediately=false — "잡이 없다" · "이미 완료된 JobInstance" · 메타데이터 장애 모두 즉시 다시 쏴도 같다. 다음 주기에 다시 시도한다.
            // 스택은 Facade 가 이미 ERROR 로 남겼으므로 여기서 다시 찍지 않는다.
            throw new JobExecutionException(exception, false);
        }
    }

    static List<String> parseBlockedBy(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawValue.split(BLOCKED_BY_SEPARATOR))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .toList();
    }
}
