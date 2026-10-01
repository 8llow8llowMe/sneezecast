package com.sneezecast.domainlayer.schedule.application.command;

import java.time.Instant;
import java.util.List;

/**
 * 스케줄 발화 한 번의 요청.
 *
 * @param jobName          실행할 배치 잡 이름 ({@code JobBuilder} 에 준 이름 = {@code --spring.batch.job.name} 에 넣는 이름)
 * @param mustNotBeRunning 이 중 하나라도 돌고 있으면 이번 발화를 건너뛴다. 자기 자신도 포함한다 — 지난 주기 실행이 아직 안 끝났는데
 *                         또 시작하면 같은 행을 두 흐름이 upsert 한다
 * @param scheduledAt      트리거의 예정 발화 시각 (cron 이 뜻하는 시각). {@code runAt} JobParameter 의 원천이다
 * @param firedAt          Quartz 가 실제로 발화한 시각. 겹침 판정의 기준 시각과 마지막 발화 지표에 쓴다
 */
public record ScheduledLaunchCommand(String jobName, List<String> mustNotBeRunning, Instant scheduledAt, Instant firedAt) {

    public ScheduledLaunchCommand {
        mustNotBeRunning = mustNotBeRunning == null ? List.of() : List.copyOf(mustNotBeRunning);
    }
}
