package com.sneezecast.global.properties;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로세스 안 Quartz 스케줄 설정. 프로세스 안에 둔 이유는 {@code QuartzScheduleConfig} javadoc.
 *
 * <p><b>켜고 끄는 스위치({@code batch.schedule.enabled})는 여기서 받지 않는다.</b> 그 판정은 {@code ScheduleEnabledCondition} 이 문자열로
 * 한다. 이 record 에 {@code Boolean enabled} 를 두면 {@code ture} 같은 오타가 불리언 변환에서 실패해 기동이 죽고, {@code yes} · {@code 1} 은
 * 조건은 꺼짐인데 여기 값만 true 가 되어 둘이 갈린다 — 스위치는 한 곳에서만 읽는다.
 *
 * <p><b>잡별 cron 필드는 아직 없다.</b> 지금 잡은 수동 전용 {@code districtImportJob} 뿐이라 쓰는 곳이 없다. 주간 잡(전수신고 · 표본감시)을
 * 붙이는 이슈에서 {@code notifiableImportCron} 같은 필드를 기본값과 함께 더한다 (entity-design §4-1).
 *
 * @param timeZone          cron 과 {@code runAt} 을 해석할 시간대. 배포 환경의 {@code -Duser.timezone} 에 기대지 않는다. 기본 Asia/Seoul
 * @param staleRunningAfter 이 시간을 넘긴 STARTED 실행은 죽은 JVM 의 잔재로 보고 겹침 판정에서 뺀다. 기본 6시간
 */
@ConfigurationProperties(prefix = "batch.schedule")
public record BatchScheduleProperties(String timeZone, Duration staleRunningAfter) {

    private static final String DEFAULT_TIME_ZONE = "Asia/Seoul";
    private static final Duration DEFAULT_STALE_RUNNING_AFTER = Duration.ofHours(6);

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
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }
}
