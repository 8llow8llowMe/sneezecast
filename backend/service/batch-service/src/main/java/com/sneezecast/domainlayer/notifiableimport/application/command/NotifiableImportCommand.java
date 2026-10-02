package com.sneezecast.domainlayer.notifiableimport.application.command;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @param currentYear  올해로 볼 연도. 계획은 이 연도와 전년이다. 기본은 {@code runAt} 의 연도이고, 백필할 때 JobParameter {@code year} 로 바꾼다
 * @param runStartedAt 실행 시작 시각 (JobParameter {@code runAt}). 적재 이력의 {@code run_started_at} 이다
 */
public record NotifiableImportCommand(int currentYear, LocalDateTime runStartedAt) {

    public NotifiableImportCommand {
        Objects.requireNonNull(runStartedAt, "runStartedAt");
    }
}
