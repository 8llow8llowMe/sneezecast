package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.ReportPurgeRequestEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReportPurgeRequestRepository extends JpaRepository<ReportPurgeRequestEntity, Long> {

    // idx_report_purge_request_member_id 를 탄다.
    boolean existsByMemberIdAndCompletedAtIsNull(Long memberId);

    /**
     * 이번 회차에 부를 미완료 요청 — 1차(아직 성공 없음)는 바로, 2차는 요청 시각이 {@code secondCallDueAt} 이하일 때만. 그 사이에 부르는 호출은
     * 완료 판정에 쓸 수 없어 상대만 바쁘게 한다. 오래된 요청부터 {@code pageable} 의 크기만큼 (반환이 List 라 count 쿼리 없이 상한만 건다).
     * {@code idx_report_purge_request_completed_at} 을 탄다.
     */
    @Query("select r from ReportPurgeRequestEntity r"
        + " where r.completedAt is null and (r.firstPurgedAt is null or r.requestedAt <= :secondCallDueAt)"
        + " order by r.requestedAt asc, r.id asc")
    List<ReportPurgeRequestEntity> findDue(LocalDateTime secondCallDueAt, Pageable pageable);

    /**
     * 파기 성공 기록. 시도 횟수를 읽어서 더하지 않고 쿼리 안에서 올리고, 이미 완료된 행은 조건에서 뺀다 — 인스턴스 둘 · 수동 실행이 같은 행을 동시에
     * 처리해도 증가분이 사라지거나 완료 시각이 덮어써지지 않는다. 첫 성공 시각은 비어 있을 때만 채운다. {@code completedAt} 이 null 이면 완료가 아니다.
     *
     * <p>벌크 갱신은 Auditing({@code @LastModifiedDate})을 타지 않으므로 {@code updatedAt} 을 같은 쿼리에서 직접 쓴다. 앞서 쌓인 변경을 먼저
     * 내보내고(flush), 끝나면 영속성 컨텍스트를 비워(clear) 같은 트랜잭션의 다음 조회가 옛 값을 캐시에서 읽지 않게 한다.
     *
     * @return 고친 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ReportPurgeRequestEntity r"
        + " set r.attemptCount = r.attemptCount + 1,"
        + " r.firstPurgedAt = coalesce(r.firstPurgedAt, :succeededAt),"
        + " r.completedAt = :completedAt,"
        + " r.updatedAt = :succeededAt"
        + " where r.id = :id and r.completedAt is null")
    int recordSuccess(Long id, LocalDateTime succeededAt, LocalDateTime completedAt);

    /**
     * 파기 실패 기록. {@link #recordSuccess} 와 같이 쿼리 안에서 올리고 완료된 행은 건드리지 않는다.
     *
     * @return 고친 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ReportPurgeRequestEntity r"
        + " set r.attemptCount = r.attemptCount + 1,"
        + " r.lastError = :lastError,"
        + " r.updatedAt = :failedAt"
        + " where r.id = :id and r.completedAt is null")
    int recordFailure(Long id, String lastError, LocalDateTime failedAt);

    /**
     * 보관 기간이 지난 완료 행을 한 번에 지운다 (파생 deleteBy 는 SELECT 후 한 건씩 remove 한다). 미완료 행({@code completed_at is null})은
     * 조건에 걸리지 않는다 — 파기는 끝날 때까지 포기하지 않는다.
     *
     * @return 지운 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ReportPurgeRequestEntity r where r.completedAt is not null and r.completedAt < :threshold")
    int deleteCompletedBefore(LocalDateTime threshold);
}
