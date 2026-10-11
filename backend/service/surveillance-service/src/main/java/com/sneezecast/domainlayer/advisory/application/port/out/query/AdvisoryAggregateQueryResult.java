package com.sneezecast.domainlayer.advisory.application.port.out.query;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;

/**
 * 초안 근거 집계 — 생성 전제(마감 · 자료 부족 아님) 판정과 인용 수치 복사에 필요한 값만 옮긴다.
 *
 * @param aggregateId 집계 행 ID
 * @param finalized   주 마감이 끝났는지 ({@code finalized_at} not null)
 */
public record AdvisoryAggregateQueryResult(
    long aggregateId,
    int participantCount,
    int symptomaticCount,
    int respiratoryCount,
    int entericCount,
    AggregateLevel level,
    String ruleVersion,
    boolean finalized
) {

}
