package com.sneezecast.domainlayer.region.application.mapper;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberInterestRegionEntity;
import com.sneezecast.domainlayer.region.domain.model.MemberInterestRegion;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberInterestRegionMapper {

    // 엔티티 -> 도메인
    MemberInterestRegion toDomainFromEntity(MemberInterestRegionEntity entity);

    // 도메인 -> 엔티티 (새 행에만 쓴다 — 고치는 경로가 없다)
    MemberInterestRegionEntity toEntityFromDomain(MemberInterestRegion domain);
}
