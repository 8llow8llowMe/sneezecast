package com.sneezecast.domainlayer.member.application.mapper;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.sneezecast.domainlayer.member.domain.model.Member;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberMapper {

    // 엔티티 -> 도메인
    Member toDomainFromEntity(MemberEntity entity);

    // 도메인 -> 엔티티
    MemberEntity toEntityFromDomain(Member domain);
}
