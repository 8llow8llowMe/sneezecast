package com.sneezecast.domainlayer.advisory.domain.model;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.util.Objects;

/**
 * 안내가 인용하는 집계 수치 (entity-design §2-3 {@code cited_*}). 초안을 만들 때 마감된 집계에서 <b>복사해 고정</b>한다 — 운영자가 검토한 수치와 발행문
 * 수치가 같아야 하므로, 집계 행이 나중에 바뀌어도 이 값은 따라가지 않는다.
 *
 * <p><b>{@link AggregateLevel#INSUFFICIENT} 는 들어갈 수 없다</b> — 자료가 충분할 때에만 예방 정보를 전한다(루트 CLAUDE.md). 수치 관계는 집계
 * ({@code AggregateCounts})와 같다.
 *
 * @param participantCount 인용 참여자 수
 * @param symptomaticCount 인용 증상 보고 수
 * @param respiratoryCount 인용 호흡기 보고 수
 * @param entericCount     인용 장관 보고 수
 * @param level            인용 단계 (NORMAL · SLIGHT · HIGH)
 * @param ruleVersion      인용 집계의 판정 규칙 버전
 */
public record AdvisoryCitation(
    int participantCount,
    int symptomaticCount,
    int respiratoryCount,
    int entericCount,
    AggregateLevel level,
    String ruleVersion
) {

    public AdvisoryCitation {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        if (level == AggregateLevel.INSUFFICIENT) {
            throw new IllegalArgumentException("자료 부족 집계는 안내에 인용할 수 없습니다.");
        }
        if (participantCount < 0 || symptomaticCount < 0 || respiratoryCount < 0 || entericCount < 0 || symptomaticCount > participantCount
            || respiratoryCount > symptomaticCount || entericCount > symptomaticCount || symptomaticCount > (long) respiratoryCount + entericCount) {
            throw new IllegalArgumentException("인용 수치 관계가 맞지 않습니다. participant=" + participantCount + ", symptomatic=" + symptomaticCount
                + ", respiratory=" + respiratoryCount + ", enteric=" + entericCount);
        }
    }

    /** 증상군의 인용 보고 수 ({@code DistrictWeeklyAggregate#groupCount} 와 같은 대응). */
    public int groupCount(SymptomGroup group) {
        return switch (group) {
            case RESPIRATORY -> respiratoryCount;
            case ENTERIC -> entericCount;
        };
    }
}
