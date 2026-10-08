package com.sneezecast.domainlayer.aggregate.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.aggregate.adapter.out.persistence.entity.DistrictWeeklyAggregateEntity;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 벌크 갱신은 Auditing({@code @LastModifiedDate})을 타지 않으므로 {@code updatedAt} 을 같은 쿼리에서 직접 쓴다. 앞서 쌓인 변경을 먼저
 * 내보내고(flush), 끝나면 영속성 컨텍스트를 비워(clear) 같은 트랜잭션의 다음 조회가 옛 값을 캐시에서 읽지 않게 한다 ({@code WeeklyReportRepository} 와 같다).
 */
public interface DistrictWeeklyAggregateRepository extends JpaRepository<DistrictWeeklyAggregateEntity, Long> {

    /** {@code idx_district_weekly_aggregate_iso_week} 를 탄다. */
    List<DistrictWeeklyAggregateEntity> findAllByIsoWeek(String isoWeek);

    /** {@code idx_district_weekly_aggregate_iso_week} 를 탄다. 기준선 · 전주 행을 한 번에 읽는다. */
    List<DistrictWeeklyAggregateEntity> findAllByIsoWeekIn(Collection<String> isoWeeks);

    /** 마감 안 된 행이 남은 지난 주. {@code YYYY-Www} 문자열 정렬이 시간 순이라 문자열로 비교한다. */
    @Query("select distinct a.isoWeek from DistrictWeeklyAggregateEntity a where a.finalizedAt is null and a.isoWeek < :isoWeek order by a.isoWeek")
    List<String> findUnfinalizedIsoWeeksBefore(String isoWeek);

    /**
     * 한 칸의 수치 · 판정을 다시 쓴다. <b>마감된 행은 고치지 않는다</b> — 조건에 {@code finalizedAt is null} 을 걸어 마감과 재계산이 엇갈려도
     * 확정된 값이 바뀌지 않는다.
     *
     * @return 고친 행 수 (0 또는 1). 행이 없거나 마감됐으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DistrictWeeklyAggregateEntity a"
        + " set a.participantCount = :participantCount, a.symptomaticCount = :symptomaticCount, a.respiratoryCount = :respiratoryCount,"
        + " a.entericCount = :entericCount, a.revisedReportCount = :revisedReportCount,"
        + " a.level = :level, a.insufficientReason = :insufficientReason,"
        + " a.baselineParticipantCount = :baselineParticipantCount, a.baselineSymptomaticCount = :baselineSymptomaticCount,"
        + " a.ruleVersion = :ruleVersion, a.calculatedAt = :calculatedAt, a.updatedAt = :updatedAt"
        + " where a.districtCode = :districtCode and a.isoWeek = :isoWeek and a.finalizedAt is null")
    int updateCalculation(String districtCode, String isoWeek, int participantCount, int symptomaticCount, int respiratoryCount, int entericCount,
        int revisedReportCount, AggregateLevel level, InsufficientReason insufficientReason, Integer baselineParticipantCount,
        Integer baselineSymptomaticCount, String ruleVersion, LocalDateTime calculatedAt, LocalDateTime updatedAt);

    /**
     * 주 하나를 마감한다. 이미 마감된 행은 마감 시각을 바꾸지 않는다 (다시 불러도 멱등).
     *
     * @return 이번에 마감한 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DistrictWeeklyAggregateEntity a set a.finalizedAt = :finalizedAt, a.updatedAt = :updatedAt"
        + " where a.isoWeek = :isoWeek and a.finalizedAt is null")
    int finalizeWeek(String isoWeek, LocalDateTime finalizedAt, LocalDateTime updatedAt);
}
