package com.sneezecast.domainlayer.report.application.service;

import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지금이 몇 번째 보고 주인지 정한다. <b>보고 주는 서버가 정한다</b> — 요청에 주 값을 받지 않는다 (entity-design §2-1 주 경계 규칙).
 *
 * <p>현재 시각은 {@link Clock} 빈({@code SurveillanceServiceBeansConfig})에서 받는다. 테스트는 고정 Clock 으로 주 경계를 재현한다.
 * 시간대는 Clock 의 zone 이 아니라 {@link ReportWeek#ZONE}(KST)으로 계산하므로, Clock 을 UTC 로 바꿔 끼워도 주 정의는 달라지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ReportWeekCalculator {

    private final Clock clock;

    /** 지금(KST)이 속한 ISO 주. 쓸 수 있는 주는 이 주뿐이다. */
    public ReportWeek currentWeek() {
        return ReportWeek.of(clock.instant());
    }
}
