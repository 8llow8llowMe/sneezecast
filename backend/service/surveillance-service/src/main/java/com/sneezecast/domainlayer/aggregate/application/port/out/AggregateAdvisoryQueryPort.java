package com.sneezecast.domainlayer.aggregate.application.port.out;

import com.sneezecast.domainlayer.aggregate.application.port.out.query.AggregateAdvisoryQueryResult;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Collection;
import java.util.List;

/**
 * 공개 집계 조회가 동네별 발행 안내를 묻는 포트. 안내는 같은 서비스의 {@code advisory} 컨텍스트가 소유하므로 aggregate 는 그 영속 계층을 직접 부르지
 * 않는다.
 */
public interface AggregateAdvisoryQueryPort {

    /**
     * 여러 동네 · 여러 주에 <b>발행 중(PUBLISHED)</b>인 안내 요약. 동네 · 주마다 많아야 하나다. 발행 중인 안내가 없는 동네 · 주(철회됨 · 승인 전 포함)는
     * 목록에 없다. 동네 · 주 수만큼 단건으로 읽지 않는다. 어느 주의 안내를 보일지는 호출자가 고른다.
     */
    List<AggregateAdvisoryQueryResult> findPublished(Collection<String> districtCodes, Collection<ReportWeek> isoWeeks);
}
