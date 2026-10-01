package com.sneezecast.domainlayer.schedule.application.port.out;

import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult.LaunchOutcome;
import java.time.Instant;

/** 스케줄 발화를 지표로 노출한다. */
public interface ScheduleMetricsPort {

    void recordFire(String jobName, LaunchOutcome outcome, Instant firedAt);
}
