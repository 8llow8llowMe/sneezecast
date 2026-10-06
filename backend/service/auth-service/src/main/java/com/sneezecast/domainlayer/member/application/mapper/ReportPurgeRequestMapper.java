package com.sneezecast.domainlayer.member.application.mapper;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ReportPurgeRequestMapper {

    // 엔티티 -> 도메인
    ReportPurgeRequest toDomainFromEntity(ReportPurgeRequestEntity entity);

    // 도메인 -> 엔티티
    ReportPurgeRequestEntity toEntityFromDomain(ReportPurgeRequest domain);
}
