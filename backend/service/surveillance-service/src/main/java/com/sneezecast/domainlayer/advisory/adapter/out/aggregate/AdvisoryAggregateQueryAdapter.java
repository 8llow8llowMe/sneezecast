package com.sneezecast.domainlayer.advisory.adapter.out.aggregate;

import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryAggregateQueryPort;
import com.sneezecast.domainlayer.advisory.application.port.out.query.AdvisoryAggregateQueryResult;
import com.sneezecast.domainlayer.aggregate.application.port.out.DistrictWeeklyAggregateRepositoryPort;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 {@code aggregate} 컨텍스트에 집계 행을 묻는다 ({@code AggregateDistrictQueryAdapter} 와 같은 방식). aggregate 의 저장 포트만 쓰고
 * 엔티티 · 리포지토리는 import 하지 않는다. UK {@code (district_code, iso_week)} 로 한 칸을 읽는다.
 *
 * <p>호출자 트랜잭션 안에서 같은 DB 를 읽으므로 외부 I/O 가 아니다.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryAggregateQueryAdapter implements AdvisoryAggregateQueryPort {

    private final DistrictWeeklyAggregateRepositoryPort districtWeeklyAggregateRepositoryPort;

    @Override
    public Optional<AdvisoryAggregateQueryResult> findByDistrictCodeAndIsoWeek(String districtCode, ReportWeek isoWeek) {
        // UK 라 많아야 한 행이다.
        return districtWeeklyAggregateRepositoryPort.findAllByDistrictCodeInAndIsoWeekIn(List.of(districtCode), List.of(isoWeek)).stream()
            .findFirst()
            .map(AdvisoryAggregateQueryAdapter::toResult);
    }

    private static AdvisoryAggregateQueryResult toResult(DistrictWeeklyAggregate aggregate) {
        return new AdvisoryAggregateQueryResult(aggregate.id(), aggregate.participantCount(), aggregate.symptomaticCount(), aggregate.respiratoryCount(),
            aggregate.entericCount(), aggregate.level(), aggregate.ruleVersion(), aggregate.finalized());
    }
}
