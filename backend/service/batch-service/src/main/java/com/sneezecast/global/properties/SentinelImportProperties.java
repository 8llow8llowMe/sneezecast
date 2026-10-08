package com.sneezecast.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 표본감시 적재 설정 (data-api-analysis §3, entity-design §4-1).
 *
 * @param recentWeeks    급성호흡기 · 장관감염증을 다시 받을 최근 주 수. 원천이 잠정 통계라 과거 주 값이 바뀐다 (entity-design §3-2).
 *                       인플루엔자는 절기 단위라 이 값이 지난 절기를 함께 받을지만 가른다
 * @param maxCallsPerRun 실행당 HTTP 호출 상한. 요청 최대 4건 × (화면 1 + 데이터 1) + 여유
 */
@ConfigurationProperties(prefix = "sentinel-import")
public record SentinelImportProperties(@DefaultValue("8") int recentWeeks, @DefaultValue("12") int maxCallsPerRun) {

    public SentinelImportProperties {
        if (recentWeeks < 1) {
            throw new IllegalArgumentException("sentinel-import.recent-weeks must be positive. value=" + recentWeeks);
        }
        if (maxCallsPerRun < 1) {
            throw new IllegalArgumentException("sentinel-import.max-calls-per-run must be positive. value=" + maxCallsPerRun);
        }
    }
}
