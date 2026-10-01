package com.sneezecast.domainlayer.schedule.application.port.in;

import com.sneezecast.domainlayer.schedule.application.command.ScheduledLaunchCommand;
import com.sneezecast.domainlayer.schedule.application.model.ScheduledLaunchResult;

/**
 * 스케줄 발화 진입점. 웹이 아니라 Quartz 트리거가 부르므로 {@code *WebUseCase} 가 아니다 ({@code DistrictImportUseCase} 와 같은 배치 진입점).
 */
public interface ScheduledJobLaunchUseCase {

    /**
     * 겹치는 잡이 돌고 있으면 띄우지 않고 {@code SKIPPED_RUNNING} 을 돌려준다. 띄우다 실패하면 {@code ScheduleException} 을 던진다.
     */
    ScheduledLaunchResult launch(ScheduledLaunchCommand command);
}
