package com.sneezecast.domainlayer.district.application.mapper;

import com.sneezecast.domainlayer.district.adapter.out.persistence.entity.DistrictEntity;
import com.sneezecast.domainlayer.district.domain.model.District;
import org.mapstruct.Mapper;

/**
 * 엔티티 → 도메인만 둔다. 행정동 행은 batch 가 JDBC 로 쓰므로 surveillance 에는 도메인 → 엔티티 방향이 없다.
 */
@Mapper(componentModel = "spring")
public interface DistrictMapper {

    District toDomainFromEntity(DistrictEntity entity);
}
