package com.sneezecast.domainlayer.aggregate.application.model;

import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule.WeekState;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;

/**
 * 주 하나를 재계산 · 마감한 결과 (로그 · 테스트용). 익명 집계 건수만 담는다.
 *
 * @param isoWeek           대상 주
 * @param weekState         판정 상태 — 현재 주 재계산은 IN_PROGRESS, 마감은 CLOSED
 * @param reportedDistricts 원시 보고가 있는 행정동 수
 * @param inserted          새로 저장한 행 수
 * @param updated           다시 쓴 행 수 (재계산 결과에 없어 0 으로 덮어쓴 행 포함)
 * @param finalized         이번에 마감한 행 수. IN_PROGRESS 면 0
 * @param alreadyFinalized  이미 마감된 주라 다시 세지 않았는지
 */
public record AggregateWeekResult(
    ReportWeek isoWeek,
    WeekState weekState,
    int reportedDistricts,
    int inserted,
    int updated,
    int finalized,
    boolean alreadyFinalized
) {

}
