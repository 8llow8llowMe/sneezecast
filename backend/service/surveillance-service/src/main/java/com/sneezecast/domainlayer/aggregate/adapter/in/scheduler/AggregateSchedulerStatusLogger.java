package com.sneezecast.domainlayer.aggregate.adapter.in.scheduler;

import com.sneezecast.domainlayer.aggregate.application.service.AggregateJobProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기동 때 집계 스케줄러가 켜졌는지 한 줄 남긴다. 꺼져 있으면 WARN 이다 — 꺼진 채 배포되면 집계 행이 생기지 않아 모든 동이 자료 부족으로 보이므로,
 * 배포가 스위치를 빠뜨린 것을 로그에서 바로 찾게 한다. yml 기본값이 꺼짐이라 테스트 컨텍스트에서도 이 WARN 이 찍힌다(정상).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateSchedulerStatusLogger {

    private final AggregateJobProperties aggregateJobProperties;

    @PostConstruct
    void logStatus() {
        if (aggregateJobProperties.isSchedulerOn()) {
            log.info("aggregate scheduler enabled fixedDelay={} closeCron=\"{}\" closeGrace={}", aggregateJobProperties.fixedDelay(),
                aggregateJobProperties.closeCron(), aggregateJobProperties.closeGrace());
        } else {
            log.warn("aggregate scheduler disabled - set SURVEILLANCE_AGGREGATE_JOB_SCHEDULER_ENABLED=true (surveillance.aggregate-job.scheduler-enabled)");
        }
    }
}
