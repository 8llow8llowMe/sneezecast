package com.sneezecast.domainlayer.aggregate.domain.model;

/**
 * 행정동 × 주 수치 (entity-design §2-2). 원시 보고를 다시 센 값이라 서로 어긋날 수 없는 관계를 생성 때 확인한다 — 어긋나면 집계 쿼리가 틀린 것이다.
 *
 * @param participantCount   참여자 수 (그 주 보고 행 수, 증상 없음 포함). 비율의 분모
 * @param symptomaticCount   증상군을 하나 이상 보고한 수
 * @param respiratoryCount   호흡기 증상군 보고 수
 * @param entericCount       장관 증상군 보고 수
 * @param revisedReportCount 그 주에 한 번 이상 수정된 보고 수
 */
public record AggregateCounts(
    int participantCount,
    int symptomaticCount,
    int respiratoryCount,
    int entericCount,
    int revisedReportCount
) {

    public AggregateCounts {
        if (participantCount < 0 || symptomaticCount < 0 || respiratoryCount < 0 || entericCount < 0 || revisedReportCount < 0) {
            throw new IllegalArgumentException("집계 수치는 0 이상이어야 합니다. " + describe(participantCount, symptomaticCount, respiratoryCount, entericCount,
                revisedReportCount));
        }
        // 증상 보고 ⊆ 참여, 증상군별 ⊆ 증상 보고, 증상 보고는 증상군을 하나 이상 가진다(둘 다면 양쪽에 센다), 수정된 보고 ⊆ 참여.
        if (symptomaticCount > participantCount || respiratoryCount > symptomaticCount || entericCount > symptomaticCount
            || symptomaticCount > (long) respiratoryCount + entericCount || revisedReportCount > participantCount) {
            throw new IllegalArgumentException("집계 수치 관계가 맞지 않습니다. " + describe(participantCount, symptomaticCount, respiratoryCount, entericCount,
                revisedReportCount));
        }
    }

    private static String describe(int participant, int symptomatic, int respiratory, int enteric, int revised) {
        return "participant=" + participant + ", symptomatic=" + symptomatic + ", respiratory=" + respiratory + ", enteric=" + enteric
            + ", revised=" + revised;
    }
}
