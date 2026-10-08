package com.sneezecast.domainlayer.sentinelimport.application.command;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @param baseDate     계획의 기준일. 이 날이 든 주를 끝으로 최근 N주와 이 날의 절기를 받는다. 기본은 {@code runAt} 의 날짜이고, 백필할 때
 *                     JobParameter {@code baseDate} 로 바꾼다
 * @param runStartedAt 실행 시작 시각 (JobParameter {@code runAt}). 적재 이력의 {@code run_started_at} 이다
 */
public record SentinelImportCommand(LocalDate baseDate, LocalDateTime runStartedAt) {

    public SentinelImportCommand {
        Objects.requireNonNull(baseDate, "baseDate");
        Objects.requireNonNull(runStartedAt, "runStartedAt");
    }
}
