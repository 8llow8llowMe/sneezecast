package com.sneezecast.domainlayer.aggregate.application.mapper;

import com.sneezecast.domainlayer.aggregate.adapter.out.persistence.entity.DistrictWeeklyAggregateEntity;
import com.sneezecast.domainlayer.aggregate.domain.model.DistrictWeeklyAggregate;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import org.mapstruct.Mapper;

/**
 * 엔티티 ↔ 도메인. 집계 주는 {@code YYYY-Www} 문자열 ↔ {@link ReportWeek} 로 바꾼다. 감사 시각은 Auditing 이 채우므로 도메인 → 엔티티 방향에서는
 * 옮기지 않는다 (엔티티 빌더에 그 필드가 없다).
 */
@Mapper(componentModel = "spring")
public interface DistrictWeeklyAggregateMapper {

    // 엔티티 -> 도메인
    DistrictWeeklyAggregate toDomainFromEntity(DistrictWeeklyAggregateEntity entity);

    // 도메인 -> 엔티티 (새 행 INSERT 에만 쓴다. 기존 행은 갱신 쿼리로 고친다 — DistrictWeeklyAggregateEntity#isNew 참고)
    DistrictWeeklyAggregateEntity toEntityFromDomain(DistrictWeeklyAggregate domain);

    default String toIsoWeek(ReportWeek isoWeek) {
        return isoWeek.value();
    }

    default ReportWeek toReportWeek(String isoWeek) {
        return ReportWeek.parse(isoWeek);
    }
}
