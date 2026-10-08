package com.sneezecast.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * 원시 보고 파기 스케줄러 설정 ({@code auth.report-purge.*}, entity-design §1-5). 값은 application.yml 의 env 자리표시자 기본값이 정본이다.
 *
 * <p>0 이하 값은 기동에서 실패시킨다 ({@link AuthSessionProperties} 와 같은 규칙). {@code initialDelay} 만 0 을 허용한다.
 *
 * @param schedulerEnabled      스케줄러를 켤지 — {@code true} / {@code false}(대소문자 무시)만 받고 그 밖의 값({@code ""} · {@code yes} · {@code 1} ·
 *                              {@code on})은 기동 실패다. 켜고 끄는 판정은 {@code @ConditionalOnProperty(havingValue = "true")}({@code ReportPurgeScheduler} ·
 *                              {@code AuthServiceSchedulingConfig})가 하는데, 그 조건은 {@code true} 가 아닌 값을 조용히 꺼짐으로 본다. boolean 으로 받으면
 *                              {@code yes} 는 여기서 켜짐 · 조건에서 꺼짐으로 갈린다. 법적 파기 의무라 오타가 조용히 끄는 대신 기동을 막는다.
 *                              키가 아예 없으면(null) 조건과 같이 꺼짐이다 — application.yml 이 항상 값을 준다
 * @param initialDelay          기동 뒤 첫 회차까지 기다리는 시간
 * @param fixedDelay            한 회차가 끝난 뒤 다음 회차까지의 간격
 * @param completionMargin      완료 판정 여유. access token 수명에 더한 시간이 지난 뒤 시작한 호출이 성공해야 완료다 — 만료 직전에 검증된 보고가
 *                              늦게 커밋되는 경우와 서버 간 시계 오차를 흡수한다
 * @param batchSize             한 회차에 부를 요청 상한
 * @param alertAttemptThreshold 실패 기록 뒤 시도 횟수가 이 값 이상이면 ERROR 경보 로그를 남긴다. 행을 지우거나 포기하지 않는다
 * @param retention             완료 행 보관 기간 (파기 증빙). 지나면 정리 스케줄러가 지운다
 * @param cleanupCron           완료 행 정리 시각 (Spring cron — 초가 맨 앞, Asia/Seoul 기준)
 */
@ConfigurationProperties(prefix = "auth.report-purge")
public record ReportPurgeProperties(
    String schedulerEnabled,
    Duration initialDelay,
    Duration fixedDelay,
    Duration completionMargin,
    int batchSize,
    int alertAttemptThreshold,
    Duration retention,
    String cleanupCron
) {

    public ReportPurgeProperties {
        if (schedulerEnabled != null && !"true".equalsIgnoreCase(schedulerEnabled) && !"false".equalsIgnoreCase(schedulerEnabled)) {
            throw new IllegalStateException("auth.report-purge.scheduler-enabled(AUTH_REPORT_PURGE_SCHEDULER_ENABLED) 는 true 또는 false 여야 합니다: "
                + schedulerEnabled);
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalStateException("auth.report-purge.initial-delay 는 0 이상이어야 합니다: " + initialDelay);
        }
        requirePositive("auth.report-purge.fixed-delay", fixedDelay);
        requirePositive("auth.report-purge.completion-margin", completionMargin);
        requirePositive("auth.report-purge.retention", retention);
        if (batchSize <= 0) {
            throw new IllegalStateException("auth.report-purge.batch-size 는 0 보다 커야 합니다: " + batchSize);
        }
        if (alertAttemptThreshold <= 0) {
            throw new IllegalStateException("auth.report-purge.alert-attempt-threshold 는 0 보다 커야 합니다: " + alertAttemptThreshold);
        }
        if (cleanupCron == null || !CronExpression.isValidExpression(cleanupCron)) {
            throw new IllegalStateException("auth.report-purge.cleanup-cron 이 올바른 cron 식이 아닙니다: " + cleanupCron);
        }
    }

    /** {@code @ConditionalOnProperty(havingValue = "true")} 와 같은 판정 — 대소문자를 무시하고 {@code true} 일 때만 켜짐. */
    public boolean isSchedulerOn() {
        return "true".equalsIgnoreCase(schedulerEnabled);
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 는 0 보다 커야 합니다: " + value);
        }
    }
}
