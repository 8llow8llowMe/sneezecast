package com.sneezecast.domainlayer.report.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.report.adapter.out.persistence.entity.WeeklyReportEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReportEntity, Long> {

    /** {@code uk_weekly_report_reporter_key_iso_week} 를 탄다. */
    Optional<WeeklyReportEntity> findByReporterKeyAndIsoWeek(String reporterKey, String isoWeek);

    /**
     * 같은 주 보고를 고친다. {@code revisionCount} 는 읽어서 더하지 않고 쿼리 안에서 올린다 — 동시 수정 두 건이 같은 값을 읽고 같은 값을 쓰면
     * 증가분 하나가 사라진다 (entity-design §2-1).
     *
     * <p>증가는 {@link WeeklyReportEntity#MAX_REVISION_COUNT}(SMALLINT 상한)에서 멈춘다(포화). 같은 주에 수만 번 고치는 남용에서도 범위 초과로
     * 수정이 500 이 되지 않게 한다 — 상한에 닿은 값도 반복 보고 검토 후보로는 충분히 드러난다.
     *
     * <p>벌크 갱신은 Auditing({@code @LastModifiedDate})을 타지 않으므로 {@code updatedAt} 을 같은 쿼리에서 직접 쓴다. 앞서 쌓인 변경을 먼저
     * 내보내고(flush), 끝나면 영속성 컨텍스트를 비워(clear) 같은 트랜잭션의 다음 조회가 옛 값을 캐시에서 읽지 않게 한다.
     *
     * @return 고친 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WeeklyReportEntity w"
        + " set w.districtCode = :districtCode,"
        + " w.symptomMask = :symptomMask,"
        + " w.revisionCount = case when w.revisionCount < " + WeeklyReportEntity.MAX_REVISION_COUNT
        + " then w.revisionCount + 1 else w.revisionCount end,"
        + " w.updatedAt = :updatedAt"
        + " where w.reporterKey = :reporterKey and w.isoWeek = :isoWeek")
    int updateCurrent(String reporterKey, String isoWeek, String districtCode, byte symptomMask, LocalDateTime updatedAt);

    /**
     * 같은 주 보고를 지운다. 행을 읽어 오지 않고 한 번에 지운다 (파생 deleteBy 는 SELECT 후 한 건씩 remove 한다).
     *
     * @return 지운 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WeeklyReportEntity w where w.reporterKey = :reporterKey and w.isoWeek = :isoWeek")
    int deleteByReporterKeyAndIsoWeek(String reporterKey, String isoWeek);
}
