package com.sneezecast.domainlayer.advisory.application.port.out;

import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryAggregateQueryResult;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryCandidateAggregateQueryResult;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 초안 근거 · 검토 후보 집계를 묻는 포트. 집계는 같은 서비스의 {@code aggregate} 컨텍스트가 소유하므로 advisory 는 그 영속 계층을 직접 부르지 않는다.
 */
public interface AdvisoryAggregateQueryPort {

    /** 동네 · 주의 집계 행. 행이 없으면 empty (마감 여부와 무관). */
    Optional<AdvisoryAggregateQueryResult> findByDistrictCodeAndIsoWeek(String districtCode, ReportWeek isoWeek);

    /** 검토 후보(#266) — 여러 주의 모든 행정동 행을 한 번에 읽는다({@code idx_district_weekly_aggregate_iso_week}). 순서는 정하지 않는다. 주가 비면 빈 목록이다. */
    List<AdvisoryCandidateAggregateQueryResult> findAllByIsoWeekIn(Collection<ReportWeek> isoWeeks);
}
