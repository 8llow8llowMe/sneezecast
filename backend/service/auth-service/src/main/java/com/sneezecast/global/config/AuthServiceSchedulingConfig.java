package com.sneezecast.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 처리를 켠다. 지금 스케줄은 원시 보고 파기({@code ReportPurgeScheduler})뿐이라 같은 스위치
 * ({@code auth.report-purge.scheduler-enabled=true})로 묶는다 — 꺼져 있으면 스케줄러 스레드도 만들지 않는다. 다른 스케줄이 생기면 이 조건을 떼고
 * 스케줄 빈마다 스위치를 둔다.
 *
 * <p>스레드는 Boot 기본 {@code taskScheduler}(1개)다. 파기 회차와 정리가 겹치면 하나가 끝날 때까지 기다린다 — 둘 다 짧고 급하지 않다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "auth.report-purge.scheduler-enabled", havingValue = "true")
public class AuthServiceSchedulingConfig {

}
