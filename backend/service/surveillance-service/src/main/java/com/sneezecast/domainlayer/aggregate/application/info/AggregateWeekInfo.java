package com.sneezecast.domainlayer.aggregate.application.info;

import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.List;

/**
 * 한 주의 공개 집계 — 동네 하나(홈) 또는 시군구 하나(지도)의 행정동들.
 *
 * @param isoWeek         집계 주
 * @param finalized       그 주가 마감됐는지 (마감된 행이 하나라도 있으면 마감 — 마감은 주 단위 벌크다). 마감 전이면 값이 계속 바뀐다
 * @param publicThreshold 수치를 공개하는 최소 참여자 수 ({@code aggregate.min-sample})
 * @param slightDeltaPp   조금 늘었어요 하한 %p ({@code aggregate.slight-delta-pp}) — 단계와 증상군 변화가 같은 값을 쓴다
 * @param highDeltaPp     많이 늘었어요 하한 %p ({@code aggregate.high-delta-pp})
 * @param districts       행정동별 집계. 요청 순서 (동네 하나면 한 건)
 */
public record AggregateWeekInfo(
    ReportWeek isoWeek,
    boolean finalized,
    int publicThreshold,
    int slightDeltaPp,
    int highDeltaPp,
    List<DistrictWeeklyAggregateInfo> districts
) {

    public AggregateWeekInfo {
        districts = List.copyOf(districts);
    }
}
