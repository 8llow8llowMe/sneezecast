package com.sneezecast.domainlayer.aggregate.domain.model;

import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 행정동 × 주 한 칸을 다시 센 결과 — 수치 · 판정 · 규칙 버전 · 계산 시각. 새 행이면 {@link DistrictWeeklyAggregate#newAggregate} 로 저장하고,
 * 있는 행이면 저장소의 갱신 연산에 그대로 넘긴다. {@link AggregateRule#calculate} 로 만들어 규칙 버전이 판정과 어긋나지 않게 한다.
 *
 * @param districtCode 행정동 코드
 * @param isoWeek      집계 주
 * @param counts       수치
 * @param judgement    단계 판정
 * @param ruleVersion  판정에 쓴 규칙 버전
 * @param calculatedAt 계산 시각 ({@code Clock} 빈 기준 지역 시각)
 */
public record AggregateCalculation(
    String districtCode,
    ReportWeek isoWeek,
    AggregateCounts counts,
    AggregateJudgement judgement,
    String ruleVersion,
    LocalDateTime calculatedAt
) {

    public AggregateCalculation {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(isoWeek, "isoWeek");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(judgement, "judgement");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
    }
}
