package com.sneezecast.global.properties;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import org.quartz.CronExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로세스 안 Quartz 스케줄 설정. 프로세스 안에 둔 이유는 {@code QuartzScheduleConfig} javadoc.
 *
 * <p><b>켜고 끄는 스위치({@code batch.schedule.enabled})는 여기서 받지 않는다.</b> 그 판정은 {@code ScheduleEnabledCondition} 이 문자열로
 * 한다. 이 record 에 {@code Boolean enabled} 를 두면 {@code ture} 같은 오타가 불리언 변환에서 실패해 기동이 죽고, {@code yes} · {@code 1} 은
 * 조건은 꺼짐인데 여기 값만 true 가 되어 둘이 갈린다 — 스위치는 한 곳에서만 읽는다.
 *
 * <p><b>cron 은 잡마다 한 필드다</b> (entity-design §4-1). 값은 Quartz cron(초가 맨 앞)이고, 시각은 {@code timeZone} 으로 해석한다. 잘못된 식은
 * 기동에서 막는다 — 트리거를 만들 때 터지면 스케줄 빈 생성 실패라 원인이 덜 드러난다. 수동 전용 {@code districtImportJob} 은 필드가 없다.
 *
 * @param timeZone          cron 과 {@code runAt} 을 해석할 시간대. 배포 환경의 {@code -Duser.timezone} 에 기대지 않는다. 기본 Asia/Seoul
 * @param staleRunningAfter 이 시간을 넘긴 STARTED 실행은 죽은 JVM 의 잔재로 보고 겹침 판정에서 뺀다. 기본 6시간
 * @param notifiableCron    {@code notifiableImportJob}(전수신고) 주기. 기본 {@code 0 0 5 ? * TUE} (매주 화 05:00)
 */
@ConfigurationProperties(prefix = "batch.schedule")
public record BatchScheduleProperties(String timeZone, Duration staleRunningAfter, String notifiableCron) {

    private static final String DEFAULT_TIME_ZONE = "Asia/Seoul";
    private static final Duration DEFAULT_STALE_RUNNING_AFTER = Duration.ofHours(6);
    static final String DEFAULT_NOTIFIABLE_CRON = "0 0 5 ? * TUE";

    public BatchScheduleProperties {
        if (timeZone == null || timeZone.isBlank()) {
            timeZone = DEFAULT_TIME_ZONE;
        }
        // TimeZone.getTimeZone("Asia/Seuol") 은 예외 없이 GMT 를 돌려준다. 오타가 조용히 9시간 밀린 발화가 되지 않게 기동에서 막는다.
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("batch.schedule.time-zone must be a valid zone id. value=" + timeZone, exception);
        }

        if (staleRunningAfter == null) {
            staleRunningAfter = DEFAULT_STALE_RUNNING_AFTER;
        }
        // 0 이하면 모든 실행이 "오래된 잔재" 가 되어 겹침 판정이 통째로 꺼진다.
        if (staleRunningAfter.isZero() || staleRunningAfter.isNegative()) {
            throw new IllegalArgumentException("batch.schedule.stale-running-after must be positive. value=" + staleRunningAfter);
        }

        if (notifiableCron == null || notifiableCron.isBlank()) {
            notifiableCron = DEFAULT_NOTIFIABLE_CRON;
        }
        notifiableCron = requireCron(notifiableCron.trim(), "batch.schedule.notifiable-cron");
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }

    /** Quartz 문법({@link CronExpression})으로 검사한다. Spring 의 {@code CronExpression} 과는 {@code ?} · 연도 필드 규칙이 다르다. */
    private static String requireCron(String cron, String key) {
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException(key + " must be a valid Quartz cron expression (seconds first). value=" + cron);
        }
        return cron;
    }
}
