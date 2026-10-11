package com.sneezecast.domainlayer.member.application.port.out;

import com.sneezecast.domainlayer.member.application.exception.MemberException;
import com.sneezecast.domainlayer.member.domain.model.MemberNotificationSetting;
import java.time.LocalDateTime;
import java.util.Optional;

public interface MemberNotificationSettingRepositoryPort {

    /** 회원의 알림 설정. 한 번도 바꾸지 않았으면 empty 다(호출자가 모두 꺼짐으로 본다). */
    Optional<MemberNotificationSetting> findByMemberId(long memberId);

    /**
     * 새 행을 넣는다. 회원에게 이미 행이 있으면 {@code uk_member_notification_setting_member_id} 위반이다 — 앞선 조회에서 없던 행이 그 사이 생긴
     * 것(같은 회원의 동시 첫 변경)이라 새 트랜잭션에서 다시 하면 갱신으로 풀린다.
     *
     * @throws MemberException {@code NOTIFICATION_SETTING_SAVE_CONFLICT}(409) — 회원당 1행 unique 위반. 호출자 트랜잭션은 롤백된다
     */
    MemberNotificationSetting insert(MemberNotificationSetting setting);

    /**
     * 기존 행에서 보낸 항목만 DB 가 원자적으로 바꾼다 — {@code null} 은 보내지 않은 항목이라 갱신하는 순간의 값을 그대로 둔다. 같은 회원의 두 요청이 서로
     * 다른 항목을 바꿔도 둘 다 남는다. 수정 시각도 함께 쓴다(벌크 갱신은 Auditing 을 타지 않는다). 호출자 트랜잭션 안에서 불러야 하고, 뒤이은
     * {@link #findByMemberId} 는 바뀐 값을 읽는다.
     *
     * @param changedAt 수정 시각({@code updated_at}) — Auditing 과 같은 기준(JVM 기본 시간대 {@code LocalDateTime.now()})
     * @return 고친 행 수. 0 이면 그 회원의 행이 없다(조회 뒤 지워졌다 — 탈퇴 정리 등)
     */
    int changeByMemberId(long memberId, Boolean weeklyReport, Boolean regionNotice, LocalDateTime changedAt);

    /**
     * 회원의 알림 설정 행을 지운다 — 탈퇴(#154) 때 회원 정리에 쓴다. 없어도 실패하지 않는다(멱등). 호출자 트랜잭션 안에서 불러야 한다.
     *
     * @return 지운 행 수 (0 또는 1)
     */
    int deleteByMemberId(long memberId);
}
