package com.sneezecast.domainlayer.aggregate.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * 집계 재계산 · 주 마감 스케줄 설정 ({@code surveillance.aggregate-job.*}, entity-design §2-2). 값은 application.yml 의 env 자리표시자 기본값이
 * 정본이다. 판정 규칙({@code aggregate.*}, {@link AggregateProperties})과 달리 결과를 바꾸지 않고 언제 도는지만 정하므로 배포 설정으로 바꿔도 된다.
 * env 이름은 {@code SURVEILLANCE_AGGREGATE_JOB_*} 다 — {@code AGGREGATE_*} 는 relaxed binding 으로 판정 규칙을 덮을 수 있어 쓰지 않는다.
 *
 * <p>잘못된 값은 기동에서 실패시킨다 (auth {@code ReportPurgeProperties} 와 같은 규칙).
 *
 * @param schedulerEnabled 스케줄러를 켤지 — {@code true} / {@code false}(대소문자 무시)만 받고 그 밖의 값은 기동 실패다. 켜고 끄는 판정은
 *                         {@code @ConditionalOnProperty(havingValue = "true")} 가 하는데, 그 조건은 {@code true} 가 아닌 값을 조용히 꺼짐으로 본다.
 *                         키가 없으면(null) 꺼짐이다
 * @param initialDelay     기동 뒤 첫 재계산까지 (0 이상)
 * @param fixedDelay       재계산 한 회차가 끝난 뒤 다음 회차까지
 * @param closeCron        주 마감 시각 (Spring cron — 초가 맨 앞, Asia/Seoul 기준). 기본 월요일 00:10
 * @param closeGrace       주가 끝난 뒤 마감하기까지 기다리는 시간 (0 이상, 1일 이하). 일요일 23:59 에 시작한 보고가 월요일에 커밋되는 경우를
 *                         흡수한다 — 재계산 회차도 이 시간이 지난 주만 마감한다. <b>grace ≤ {@code closeCron} 의 월요일 시각</b>으로 둔다
 *                         — 크면 cron 회차가 지난 주를 못 닫고 다음 주기 회차로 밀린다. 기본은 경계(0ms)를 피해 1분 여유를 둔다(9분 · 00:10)
 */
@ConfigurationProperties(prefix = "surveillance.aggregate-job")
public record AggregateJobProperties(
    String schedulerEnabled,
    Duration initialDelay,
    Duration fixedDelay,
    String closeCron,
    Duration closeGrace
) {

    private static final Duration MAX_CLOSE_GRACE = Duration.ofDays(1);

    public AggregateJobProperties {
        if (schedulerEnabled != null && !"true".equalsIgnoreCase(schedulerEnabled) && !"false".equalsIgnoreCase(schedulerEnabled)) {
            throw new IllegalStateException(
                "surveillance.aggregate-job.scheduler-enabled(SURVEILLANCE_AGGREGATE_JOB_SCHEDULER_ENABLED) 는 true 또는 false 여야 합니다: " + schedulerEnabled);
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalStateException("surveillance.aggregate-job.initial-delay 는 0 이상이어야 합니다: " + initialDelay);
        }
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
            throw new IllegalStateException("surveillance.aggregate-job.fixed-delay 는 0 보다 커야 합니다: " + fixedDelay);
        }
        if (closeCron == null || !CronExpression.isValidExpression(closeCron)) {
            throw new IllegalStateException("surveillance.aggregate-job.close-cron 이 올바른 cron 식이 아닙니다: " + closeCron);
        }
        if (closeGrace == null || closeGrace.isNegative() || closeGrace.compareTo(MAX_CLOSE_GRACE) > 0) {
            throw new IllegalStateException("surveillance.aggregate-job.close-grace 는 0 이상 1일 이하여야 합니다: " + closeGrace);
        }
    }

    /** {@code @ConditionalOnProperty(havingValue = "true")} 와 같은 판정 — 대소문자를 무시하고 {@code true} 일 때만 켜짐. */
    public boolean isSchedulerOn() {
        return "true".equalsIgnoreCase(schedulerEnabled);
    }
}
