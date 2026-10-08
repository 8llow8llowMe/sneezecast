package com.sneezecast.domainlayer.aggregate.domain.model;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import java.util.Objects;

/**
 * 단계 판정 결과 (entity-design §2-2). 판정에 쓴 기준선을 두 수(합산 참여 · 합산 증상)로 함께 남긴다 — 비율은 저장하지 않고 조회 때 계산한다.
 *
 * <p>불변식: {@link AggregateLevel#INSUFFICIENT} 이면 이유가 있고 기준선이 없다. 그 밖의 단계는 이유가 없고 기준선이 있다 (기준선 없이는
 * 늘었는지 판단할 수 없다).
 *
 * @param level                    단계
 * @param insufficientReason       자료 부족 이유. INSUFFICIENT 일 때만
 * @param baselineParticipantCount 기준선 주들의 참여자 합. 기준선이 없으면 null
 * @param baselineSymptomaticCount 기준선 주들의 증상 보고 합. 기준선이 없으면 null
 */
public record AggregateJudgement(
    AggregateLevel level,
    InsufficientReason insufficientReason,
    Integer baselineParticipantCount,
    Integer baselineSymptomaticCount
) {

    public AggregateJudgement {
        Objects.requireNonNull(level, "level");
        boolean insufficient = level == AggregateLevel.INSUFFICIENT;
        if (insufficient != (insufficientReason != null)) {
            throw new IllegalArgumentException("자료 부족 이유는 INSUFFICIENT 일 때만 있어야 합니다. level=" + level + ", reason=" + insufficientReason);
        }
        if (insufficient != (baselineParticipantCount == null) || insufficient != (baselineSymptomaticCount == null)) {
            throw new IllegalArgumentException("기준선은 INSUFFICIENT 가 아닐 때만 두 수 모두 있어야 합니다. level=" + level);
        }
        if (!insufficient && (baselineParticipantCount <= 0 || baselineSymptomaticCount < 0 || baselineSymptomaticCount > baselineParticipantCount)) {
            throw new IllegalArgumentException(
                "기준선 수치가 맞지 않습니다. participant=" + baselineParticipantCount + ", symptomatic=" + baselineSymptomaticCount);
        }
    }

    /** 자료 부족 — 기준선 없이 이유만 남긴다. */
    public static AggregateJudgement insufficient(InsufficientReason reason) {
        return new AggregateJudgement(AggregateLevel.INSUFFICIENT, Objects.requireNonNull(reason, "reason"), null, null);
    }

    /** 기준선과 비교해 정한 단계. */
    public static AggregateJudgement judged(AggregateLevel level, int baselineParticipantCount, int baselineSymptomaticCount) {
        return new AggregateJudgement(level, null, baselineParticipantCount, baselineSymptomaticCount);
    }
}
