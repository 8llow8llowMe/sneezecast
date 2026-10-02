package com.sneezecast.domainlayer.region.application.mapper;

import com.sneezecast.domainlayer.region.adapter.out.persistence.entity.MemberRegionEntity;
import com.sneezecast.domainlayer.region.domain.model.MemberRegion;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberRegionMapper {

    // 엔티티 -> 도메인
    MemberRegion toDomainFromEntity(MemberRegionEntity entity);

    // 도메인 -> 엔티티 (새 행에만 쓴다 — 기존 행은 조회한 엔티티를 바꾼다)
    MemberRegionEntity toEntityFromDomain(MemberRegion domain);
}
