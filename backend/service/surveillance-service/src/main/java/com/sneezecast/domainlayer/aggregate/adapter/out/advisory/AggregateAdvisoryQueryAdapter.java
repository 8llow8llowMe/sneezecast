package com.sneezecast.domainlayer.aggregate.adapter.out.advisory;

import com.sneezecast.domainlayer.advisory.application.port.out.AdvisoryRepositoryPort;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.aggregate.application.port.out.AggregateAdvisoryQueryPort;
import com.sneezecast.domainlayer.aggregate.application.port.out.query.AggregateAdvisoryQueryResult;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 {@code advisory} 컨텍스트에 발행 중인 안내를 묻는다 ({@code AdvisoryAggregateQueryAdapter} 의 반대 방향, 같은 방식). advisory 의 저장
 * 포트만 쓰고 엔티티 · 리포지토리는 import 하지 않는다. 동네 코드 IN × 주 IN 한 번으로 읽는다.
 *
 * <p>같은 동네 · 주에 발행 중인 안내는 하나다(entity-design §2-3). 조회는 잠그지 않으므로 규칙이 깨진 행이 있어도 500 을 내지 않고 동네 · 주마다 가장
 * 최근 발행 하나({@link Advisory#PUBLICATION_ORDER})를 고르고 WARN 을 남긴다 — 동네 안내 API({@code AdvisoryPublicQueryProcessor})와 같은 선택이다.
 *
 * <p>호출자(Facade) 읽기 트랜잭션 안에서 같은 DB 를 읽으므로 외부 I/O 가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateAdvisoryQueryAdapter implements AggregateAdvisoryQueryPort {

    private final AdvisoryRepositoryPort advisoryRepositoryPort;

    @Override
    public List<AggregateAdvisoryQueryResult> findPublished(Collection<String> districtCodes, Collection<ReportWeek> isoWeeks) {
        Map<String, List<Advisory>> byDistrictWeek = advisoryRepositoryPort.findAllPublishedByDistrictCodeInAndIsoWeekIn(districtCodes, isoWeeks).stream()
            .collect(Collectors.groupingBy(advisory -> advisory.districtCode() + "/" + advisory.isoWeek().value()));
        return byDistrictWeek.values().stream()
            .map(AggregateAdvisoryQueryAdapter::latest)
            .map(advisory -> new AggregateAdvisoryQueryResult(advisory.districtCode(), advisory.isoWeek(), advisory.title(), advisory.publishedAt()))
            .toList();
    }

    private static Advisory latest(List<Advisory> advisories) {
        Advisory latest = advisories.stream().max(Advisory.PUBLICATION_ORDER).orElseThrow();
        if (advisories.size() > 1) {
            log.warn("advisory multiple published districtCode={} isoWeek={} count={}", latest.districtCode(), latest.isoWeek(), advisories.size());
        }
        return latest;
    }
}
