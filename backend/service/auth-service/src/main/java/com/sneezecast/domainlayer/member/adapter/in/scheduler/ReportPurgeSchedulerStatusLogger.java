package com.sneezecast.domainlayer.member.adapter.in.scheduler;

import com.sneezecast.global.properties.ReportPurgeProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기동 때 파기 스케줄러가 켜졌는지 한 줄 남긴다. 꺼져 있으면 WARN 이다 — 동의 철회 · 탈퇴의 법적 파기 의무 기능이라, 배포가 스위치를 빠뜨려 조용히
 * 꺼진 채로 도는 일을 로그에서 바로 찾게 한다. yml 기본값이 꺼짐이라 테스트 컨텍스트에서도 이 WARN 이 찍힌다(정상).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPurgeSchedulerStatusLogger {

    private final ReportPurgeProperties reportPurgeProperties;

    @PostConstruct
    void logStatus() {
        if (reportPurgeProperties.isSchedulerOn()) {
            log.info("report purge scheduler enabled fixedDelay={} batchSize={}", reportPurgeProperties.fixedDelay(), reportPurgeProperties.batchSize());
        } else {
            log.warn("report purge scheduler disabled - set AUTH_REPORT_PURGE_SCHEDULER_ENABLED=true (auth.report-purge.scheduler-enabled)");
        }
    }
}
