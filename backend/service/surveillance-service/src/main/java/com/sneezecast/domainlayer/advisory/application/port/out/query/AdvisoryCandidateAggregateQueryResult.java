package com.sneezecast.domainlayer.advisory.application.port.out.query;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;

/**
 * 검토 후보(#266)가 읽는 집계 한 칸 — 신호 판정(참여 · 수정 수 · 단계 · 자료 부족 이유)과 후보 수치(증상 수 · 증상군 · 판정에 쓴 기준선)에 필요한 값만
 * 옮긴다. 익명 집계라 보고자 정보가 없다.
 *
 * @param baselineParticipantCount 판정에 쓴 기준선 참여자 합. 자료 부족이면 null
 * @param baselineSymptomaticCount 판정에 쓴 기준선 증상 보고 합. 자료 부족이면 null
 * @param finalized                주 마감이 끝났는지 ({@code finalized_at} not null)
 */
public record AdvisoryCandidateAggregateQueryResult(
    String districtCode,
    ReportWeek isoWeek,
    int participantCount,
    int symptomaticCount,
    int respiratoryCount,
    int entericCount,
    int revisedReportCount,
    AggregateLevel level,
    InsufficientReason insufficientReason,
    Integer baselineParticipantCount,
    Integer baselineSymptomaticCount,
    boolean finalized
) {

}
