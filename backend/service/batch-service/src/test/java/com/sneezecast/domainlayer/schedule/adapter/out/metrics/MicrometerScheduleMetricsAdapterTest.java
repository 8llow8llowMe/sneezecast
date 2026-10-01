package com.sneezecast.domainlayer.schedule.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult.LaunchOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지표 이름 · 태그는 대시보드와 알림 규칙이 기대는 계약이라 hondigagae 와 같은 값으로 고정한다. */
class MicrometerScheduleMetricsAdapterTest {

    private static final String JOB_NAME = "notifiableImportJob";
    private static final Instant FIRST_FIRE = Instant.parse("2026-10-05T20:00:00Z");
    private static final Instant SECOND_FIRE = Instant.parse("2026-10-12T20:00:00Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerScheduleMetricsAdapter adapter = new MicrometerScheduleMetricsAdapter(meterRegistry);

    @Test
    @DisplayName("발화마다 job · result(소문자) 태그 Counter 를 올리고, 마지막 발화 시각 Gauge 는 결과와 무관하게 epoch 초로 남긴다")
    void countsFiresAndTracksLastFire() {
        adapter.recordFire(JOB_NAME, LaunchOutcome.LAUNCHED, FIRST_FIRE);
        adapter.recordFire(JOB_NAME, LaunchOutcome.SKIPPED_RUNNING, SECOND_FIRE);

        assertThat(fireCount("launched")).isEqualTo(1.0);
        assertThat(fireCount("skipped_running")).isEqualTo(1.0);
        assertThat(lastFire()).isEqualTo(SECOND_FIRE.getEpochSecond());
    }

    @Test
    @DisplayName("늦게 도착한 과거 발화가 마지막 발화 시각을 되돌리지 않는다")
    void keepsLatestFireTime() {
        adapter.recordFire(JOB_NAME, LaunchOutcome.FAILED, SECOND_FIRE);
        adapter.recordFire(JOB_NAME, LaunchOutcome.FAILED, FIRST_FIRE);

        assertThat(fireCount("failed")).isEqualTo(2.0);
        assertThat(lastFire()).isEqualTo(SECOND_FIRE.getEpochSecond());
    }

    private double fireCount(String result) {
        return meterRegistry.get(MicrometerScheduleMetricsAdapter.FIRE_METRIC).tags("job", JOB_NAME, "result", result).counter().count();
    }

    private double lastFire() {
        return meterRegistry.get(MicrometerScheduleMetricsAdapter.LAST_FIRE_METRIC).tags("job", JOB_NAME).gauge().value();
    }
}
