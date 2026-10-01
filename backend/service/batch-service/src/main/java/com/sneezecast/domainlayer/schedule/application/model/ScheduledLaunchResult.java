package com.sneezecast.domainlayer.schedule.application.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 스케줄 발화 한 번의 결과.
 *
 * @param jobName     발화 대상 잡 이름
 * @param runAt       잡에 넘긴 {@code runAt} JobParameter (예정 발화 시각을 설정 시간대(기본 KST) 초 단위로 자른 로컬 시각, {@code 2026-10-06T05:00:00})
 * @param outcome     발화 처리 결과
 * @param executionId 실제로 띄웠을 때의 JobExecution id. 건너뛰면 null
 */
public record ScheduledLaunchResult(String jobName, String runAt, LaunchOutcome outcome, Long executionId) {

    /** 반복되는 구분 값이라 enum 으로 둔다 (coding-conventions §6-3 · §7). {@code name()} 소문자가 지표의 {@code result} 태그다. */
    @Getter
    @RequiredArgsConstructor
    public enum LaunchOutcome {

        LAUNCHED("실행", "잡을 띄웠다."),
        SKIPPED_RUNNING("건너뜀", "겹치면 안 되는 잡이 돌고 있어 이번 주기를 넘겼다."),
        FAILED("실패", "띄우려다 실패했다.");

        private final String displayName;
        private final String description;
    }
}
