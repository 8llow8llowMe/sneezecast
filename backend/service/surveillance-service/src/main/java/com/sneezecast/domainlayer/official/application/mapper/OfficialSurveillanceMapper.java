package com.sneezecast.domainlayer.official.application.mapper;

import com.sneezecast.domainlayer.official.adapter.out.persistence.entity.OfficialSurveillanceEntity;
import com.sneezecast.domainlayer.official.domain.model.OfficialObservation;
import org.mapstruct.Mapper;

/**
 * 엔티티 → 도메인만 둔다. 감시 자료 행은 batch 가 JDBC 로 쓰므로 surveillance 에는 도메인 → 엔티티 방향이 없다.
 */
@Mapper(componentModel = "spring")
public interface OfficialSurveillanceMapper {

    OfficialObservation toDomainFromEntity(OfficialSurveillanceEntity entity);
}
