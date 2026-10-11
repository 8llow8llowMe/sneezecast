package com.sneezecast.domainlayer.aggregate.application.port.out;

import com.sneezecast.domainlayer.aggregate.domain.model.AggregateCounts;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.List;
import java.util.Map;

/**
 * 원시 보고 집계 조회 포트. 주간 보고는 같은 서비스의 {@code report} 컨텍스트가 소유하므로, aggregate 는 그 영속 계층을 직접 부르지 않고 이 포트로만
 * 수를 읽는다. 보고자 · 보고 행은 받지 않는다.
 */
public interface AggregateReportQueryPort {

    /**
     * 주 하나를 행정동별로 다시 센다 — 참여자(증상 없음 포함) · 증상 보고 · 증상군별 · 수정된 보고 수. 보고가 없는 행정동은 키가 없다.
     *
     * <p>같은 사람의 같은 주 보고는 한 행이라(수정은 그 행을 고친다) 참여자는 그 주 보고자 수와 같다.
     */
    Map<String, AggregateCounts> countByDistrict(ReportWeek isoWeek);

    /** {@code [fromInclusive, toExclusive)} 에서 보고가 한 건이라도 있는 주, 오래된 순. */
    List<ReportWeek> findReportedWeeks(ReportWeek fromInclusive, ReportWeek toExclusive);
}
