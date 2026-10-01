package com.sneezecast.domainlayer.schedule.adapter.out.metrics;

import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult.LaunchOutcome;
import com.sneezecast.domainlayer.schedule.application.port.out.ScheduleMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 스케줄 발화를 Micrometer 로 노출한다. 이름 · 태그는 hondigagae batch-service 와 같다.
 *
 * <ul>
 *   <li>{@code batch.schedule.fire} (Counter, tag {@code job} · {@code result}) — "지난 몇 주에 몇 번 건너뛰었나" 를 보는 누적값이다.
 *       {@code result} 는 {@code launched} · {@code skipped_running} · {@code failed}.</li>
 *   <li>{@code batch.schedule.last.fire.timestamp} (Gauge, tag {@code job}, epoch 초) — 결과와 무관하게 기록한다. 이 값이 답하는 것은 "잡이
 *       성공했나" 가 아니라 <b>"스케줄러가 살아서 발화는 하고 있나"</b> 다. 프로세스가 죽었거나 트리거가 등록되지 않은 상태는 이 값이 멈추는
 *       것으로만 드러난다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MicrometerScheduleMetricsAdapter implements ScheduleMetricsPort {

    static final String FIRE_METRIC = "batch.schedule.fire";
    static final String LAST_FIRE_METRIC = "batch.schedule.last.fire.timestamp";

    private final MeterRegistry meterRegistry;

    // Micrometer 게이지는 대상 객체를 약한 참조로 잡는다. 값 홀더를 여기서 강하게 붙들지 않으면 GC 뒤 NaN 이 된다.
    private final ConcurrentMap<String, AtomicLong> lastFireEpochByJob = new ConcurrentHashMap<>();

    @Override
    public void recordFire(String jobName, LaunchOutcome outcome, Instant firedAt) {
        Counter.builder(FIRE_METRIC)
            .description("Scheduled batch job fire count")
            .tags(Tags.of("job", jobName, "result", outcome.name().toLowerCase(Locale.ROOT)))
            .register(meterRegistry)
            .increment();

        lastFireEpochByJob.computeIfAbsent(jobName, key -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(LAST_FIRE_METRIC, holder, AtomicLong::get)
                .description("Last scheduled fire time per job (epoch seconds)")
                .tags(Tags.of("job", key))
                .register(meterRegistry);
            return holder;
        }).accumulateAndGet(firedAt.getEpochSecond(), Math::max);
    }
}
