package com.sneezecast.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 처리를 켠다. 지금 스케줄은 집계 재계산 · 주 마감({@code AggregateScheduler})뿐이라 같은 스위치
 * ({@code surveillance.aggregate-job.scheduler-enabled=true})로 묶는다 — 꺼져 있으면 스케줄러 스레드도 만들지 않는다. 다른 스케줄(보관 기간 삭제
 * 등)이 생기면 이 조건을 떼고 스케줄 빈마다 스위치를 둔다.
 *
 * <p>스레드는 Boot 기본 {@code taskScheduler}(1개)다. 재계산 회차와 마감 cron 이 겹치면 하나가 끝날 때까지 기다린다. 풀을 늘려도 스케줄러가 잠금으로
 * 한 회차만 돌린다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "surveillance.aggregate-job.scheduler-enabled", havingValue = "true")
public class SurveillanceServiceSchedulingConfig {

}
