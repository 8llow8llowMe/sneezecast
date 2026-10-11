package com.sneezecast.domainlayer.member.application.mapper;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberNotificationSettingEntity;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberNotificationSettingMapper {

    // 엔티티 -> 도메인
    MemberNotificationSetting toDomainFromEntity(MemberNotificationSettingEntity entity);

    // 도메인 -> 엔티티 (새 행에만 쓴다 — 기존 행은 조회한 엔티티를 바꾼다)
    MemberNotificationSettingEntity toEntityFromDomain(MemberNotificationSetting domain);
}
