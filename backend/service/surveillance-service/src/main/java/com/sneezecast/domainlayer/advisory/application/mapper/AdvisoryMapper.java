package com.sneezecast.domainlayer.advisory.application.mapper;

import com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity.AdvisoryEntity;
import com.sneezecast.domainlayer.advisory.domain.model.Advisory;
import com.sneezecast.domainlayer.advisory.domain.model.AdvisoryCitation;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 엔티티 ↔ 도메인. 인용 수치는 엔티티의 {@code cited_*} 컬럼 ↔ 도메인의 {@link AdvisoryCitation} 하나로, 주는 {@code YYYY-Www} ↔ {@link ReportWeek}
 * 로 바꾼다. 감사 시각 · 버전은 저장할 때 정해지므로 도메인 → 엔티티 방향에서는 옮기지 않는다.
 */
@Mapper(componentModel = "spring")
public interface AdvisoryMapper {

    // 엔티티 -> 도메인
    @Mapping(target = "citation", source = "entity")
    Advisory toDomainFromEntity(AdvisoryEntity entity);

    // 도메인 -> 엔티티 (새 행 INSERT 에만 쓴다. 기존 행은 조회한 엔티티를 바꾼다 — AdvisoryEntity#applyState)
    @Mapping(target = "citedParticipantCount", source = "citation.participantCount")
    @Mapping(target = "citedSymptomaticCount", source = "citation.symptomaticCount")
    @Mapping(target = "citedRespiratoryCount", source = "citation.respiratoryCount")
    @Mapping(target = "citedEntericCount", source = "citation.entericCount")
    @Mapping(target = "citedLevel", source = "citation.level")
    @Mapping(target = "citedRuleVersion", source = "citation.ruleVersion")
    @Mapping(target = "version", ignore = true)
    AdvisoryEntity toEntityFromDomain(Advisory domain);

    default AdvisoryCitation toCitation(AdvisoryEntity entity) {
        return new AdvisoryCitation(entity.getCitedParticipantCount(), entity.getCitedSymptomaticCount(), entity.getCitedRespiratoryCount(),
            entity.getCitedEntericCount(), entity.getCitedLevel(), entity.getCitedRuleVersion());
    }

    default String toIsoWeek(ReportWeek isoWeek) {
        return isoWeek.value();
    }

    default ReportWeek toReportWeek(String isoWeek) {
        return ReportWeek.parse(isoWeek);
    }
}
