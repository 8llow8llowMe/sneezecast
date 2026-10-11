package com.sneezecast.domainlayer.member.adapter.out.persistence.repository;

import com.sneezecast.domainlayer.member.adapter.out.persistence.entity.MemberNotificationSettingEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberNotificationSettingRepository extends JpaRepository<MemberNotificationSettingEntity, Long> {

    // uk_member_notification_setting_member_id 를 탄다. 회원당 1행이라 Optional 이다.
    Optional<MemberNotificationSettingEntity> findByMemberId(Long memberId);

    /**
     * 보낸 항목만 DB 에서 바꾼다 — {@code null} 인 항목은 {@code coalesce} 로 지금 값을 그대로 둔다. 엔티티 변경 감지는 두 칸을 모두 쓰는 UPDATE 를
     * 내보내서, 같은 회원의 두 요청이 서로 다른 항목을 바꾸면 늦게 커밋한 쪽이 먼저 바꾼 항목을 읽어 둔 옛 값으로 되돌린다. 이 쿼리는 행을 갱신하는 순간의
     * 값에 보낸 항목만 얹으므로(DB 가 원자적으로 처리) 그런 유실이 없다.
     *
     * <p>벌크 갱신은 Auditing({@code @LastModifiedDate})을 타지 않아 {@code updatedAt} 을 같은 쿼리에서 직접 쓴다. 앞서 쌓인 변경을 먼저 내보내고
     * (flush), 끝나면 영속성 컨텍스트를 비워(clear) 같은 트랜잭션의 다음 조회가 옛 엔티티를 캐시에서 읽지 않게 한다
     * ({@code ReportPurgeRequestRepository#recordSuccess} 와 같은 규칙).
     *
     * @return 고친 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MemberNotificationSettingEntity s"
        + " set s.weeklyReport = coalesce(:weeklyReport, s.weeklyReport),"
        + " s.regionNotice = coalesce(:regionNotice, s.regionNotice),"
        + " s.updatedAt = :changedAt"
        + " where s.memberId = :memberId")
    int changeByMemberId(Long memberId, Boolean weeklyReport, Boolean regionNotice, LocalDateTime changedAt);

    /**
     * 벌크 DELETE 로 지운다(탈퇴 정리용). 파생 {@code deleteBy...} 는 조회한 엔티티를 하나씩 remove 해서 겹친 두 요청 중 늦은 쪽이 "지울 행 0건" 으로
     * 실패(StaleState)한다 — 여기서는 0건도 정상(멱등)이다.
     *
     * @return 지운 행 수 (0 또는 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberNotificationSettingEntity s where s.memberId = :memberId")
    int deleteByMemberId(Long memberId);
}
