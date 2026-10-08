package com.sneezecast.domainlayer.aggregate.domain.model;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.Builder;

/**
 * 행정동 × 주 집계 한 행 (entity-design §2-2). 익명 집계라 회원 · 보고자 정보가 없다.
 *
 * <p>수치 관계는 {@link AggregateCounts}, 단계 · 이유 · 기준선 관계는 {@link AggregateJudgement} 의 불변식을 그대로 따른다 — 생성 때 두 값으로
 * 한 번 묶어 확인한다.
 *
 * @param id                       Snowflake. 새 행은 호출자가 정해서 넘긴다
 * @param districtCode             행정동 코드
 * @param isoWeek                  집계 주
 * @param participantCount         참여자 수 (분모)
 * @param symptomaticCount         증상군 하나 이상 보고 수
 * @param respiratoryCount         호흡기 증상군 보고 수
 * @param entericCount             장관 증상군 보고 수
 * @param revisedReportCount       한 번 이상 수정된 보고 수
 * @param level                    단계
 * @param insufficientReason       자료 부족 이유 (INSUFFICIENT 일 때만)
 * @param baselineParticipantCount 판정에 쓴 기준선 참여자 합 (기준선이 없으면 null)
 * @param baselineSymptomaticCount 판정에 쓴 기준선 증상 보고 합 (기준선이 없으면 null)
 * @param ruleVersion              판정 규칙 버전
 * @param calculatedAt             마지막 재계산 시각
 * @param finalizedAt              주 마감 시각. 마감 전이면 null — 마감된 행은 다시 계산하지 않는다
 * @param createdAt                처음 저장한 시각. 저장 전이면 null
 * @param updatedAt                마지막으로 고친 시각. 저장 전이면 null
 */
@Builder
public record DistrictWeeklyAggregate(
    long id,
    String districtCode,
    ReportWeek isoWeek,
    int participantCount,
    int symptomaticCount,
    int respiratoryCount,
    int entericCount,
    int revisedReportCount,
    AggregateLevel level,
    InsufficientReason insufficientReason,
    Integer baselineParticipantCount,
    Integer baselineSymptomaticCount,
    String ruleVersion,
    LocalDateTime calculatedAt,
    LocalDateTime finalizedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public DistrictWeeklyAggregate {
        Objects.requireNonNull(districtCode, "districtCode");
        Objects.requireNonNull(isoWeek, "isoWeek");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        try {
            new AggregateCounts(participantCount, symptomaticCount, respiratoryCount, entericCount, revisedReportCount);
            new AggregateJudgement(level, insufficientReason, baselineParticipantCount, baselineSymptomaticCount);
        } catch (IllegalArgumentException exception) {
            // DB 에서 읽은 행이 어기면 어느 행인지 알아야 고칠 수 있다. 익명 집계라 식별 값을 실어도 된다.
            throw new IllegalArgumentException(
                "집계 행이 불변식을 어깁니다. id=" + id + ", district=" + districtCode + ", week=" + isoWeek.value() + " — " + exception.getMessage(), exception);
        }
    }

    /** 아직 저장하지 않은 새 행. 마감 전이고 감사 시각은 저장할 때 Auditing 이 채운다. */
    public static DistrictWeeklyAggregate newAggregate(long id, AggregateCalculation calculation) {
        AggregateCounts counts = calculation.counts();
        AggregateJudgement judgement = calculation.judgement();
        return new DistrictWeeklyAggregate(id, calculation.districtCode(), calculation.isoWeek(), counts.participantCount(), counts.symptomaticCount(),
            counts.respiratoryCount(), counts.entericCount(), counts.revisedReportCount(), judgement.level(), judgement.insufficientReason(),
            judgement.baselineParticipantCount(), judgement.baselineSymptomaticCount(), calculation.ruleVersion(), calculation.calculatedAt(), null,
            null, null);
    }

    /** 주 마감이 끝났는지. 마감된 행만 다음 주들의 기준선이 될 수 있다. */
    public boolean finalized() {
        return finalizedAt != null;
    }
}
