package com.sneezecast.domainlayer.advisory.application.mapper;

import com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity.AdvisoryHistoryEntity;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryHistory;
import org.mapstruct.Mapper;

/**
 * 엔티티 ↔ 도메인. 전이 시각({@code created_at})은 Auditing 이 채우므로 도메인 → 엔티티 방향에서는 옮기지 않는다 (엔티티 빌더에 그 필드가 없다).
 */
@Mapper(componentModel = "spring")
public interface AdvisoryHistoryMapper {

    // 엔티티 -> 도메인
    AdvisoryHistory toDomainFromEntity(AdvisoryHistoryEntity entity);

    // 도메인 -> 엔티티 (INSERT 에만 쓴다 — 이력은 고치지 않는다)
    AdvisoryHistoryEntity toEntityFromDomain(AdvisoryHistory domain);
}
