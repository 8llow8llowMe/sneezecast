package com.sneezecast.domainlayer.report.application.mapper;

import com.sneezecast.domainlayer.report.adapter.out.persistence.entity.WeeklyReportEntity;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 엔티티 ↔ 도메인. 보고 주는 {@code YYYY-Www} 문자열 ↔ {@link ReportWeek}, 증상군은 비트 마스크 ↔ {@link SymptomGroup} 집합으로 바꾼다.
 * 감사 시각은 Auditing 이 채우므로 도메인 → 엔티티 방향에서는 옮기지 않는다 (엔티티 빌더에 그 필드가 없다).
 */
@Mapper(componentModel = "spring")
public interface WeeklyReportMapper {

    // 엔티티 -> 도메인
    @Mapping(target = "symptoms", source = "symptomMask")
    WeeklyReport toDomainFromEntity(WeeklyReportEntity entity);

    // 도메인 -> 엔티티 (새 행 INSERT 에만 쓴다. 기존 행은 갱신 쿼리로 고친다 — WeeklyReportEntity#isNew 참고)
    @Mapping(target = "symptomMask", source = "symptoms")
    WeeklyReportEntity toEntityFromDomain(WeeklyReport domain);

    default String toIsoWeek(ReportWeek isoWeek) {
        return isoWeek.value();
    }

    default ReportWeek toReportWeek(String isoWeek) {
        return ReportWeek.parse(isoWeek);
    }

    default byte toSymptomMask(Set<SymptomGroup> symptoms) {
        return (byte) SymptomGroup.toMask(symptoms);
    }

    default Set<SymptomGroup> toSymptoms(byte symptomMask) {
        return SymptomGroup.fromMask(symptomMask);
    }
}
