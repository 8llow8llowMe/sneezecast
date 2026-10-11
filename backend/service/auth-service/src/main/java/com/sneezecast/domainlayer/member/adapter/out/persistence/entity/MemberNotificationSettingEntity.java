package com.sneezecast.domainlayer.member.adapter.out.persistence.entity;

import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.springframework.data.domain.Persistable;

/**
 * 회원의 알림 수신 설정 (entity-design §1-7). 회원당 최대 1행이고 회원은 raw FK 로만 참조한다(DB FK 제약 없음). 행은 첫 변경 때 만든다 — 조회는 행이
 * 없으면 모두 꺼짐으로 본다. 켠 · 끈 이력은 남기지 않는다(마지막으로 바꾼 시각은 감사 컬럼 {@code updated_at}).
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "member_notification_setting",
    indexes = {
        // 회원당 1행을 DB 가 지킨다 — 동시에 들어온 첫 변경 두 건 중 하나는 여기서 막히고, 저장소 어댑터가 도메인 예외로 바꾼다(Facade 가 한 번 다시 한다).
        // 회원 기준 조회 인덱스도 겸한다.
        @Index(name = MemberNotificationSettingEntity.MEMBER_ID_UNIQUE_INDEX, columnList = "memberId", unique = true)
    })
public class MemberNotificationSettingEntity extends BaseEntity implements Persistable<Long> {

    public static final String MEMBER_ID_UNIQUE_INDEX = "uk_member_notification_setting_member_id";

    @Id
    @Comment("회원 알림 설정 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false)
    @Comment("회원 아이디 (FK: member.id)")
    private Long memberId;

    @Column(nullable = false)
    @Comment("주간 보고 요청 알림 수신 여부. 발송 때 건강정보 동의(report:write) 보유 회원으로 다시 거른다")
    private boolean weeklyReport;

    @Column(nullable = false)
    @Comment("동네 안내(운영자 발행) 알림 수신 여부. 어느 동네를 보낼지는 발송 대상 규칙이 정한다")
    private boolean regionNotice;

    /*
     * 값을 바꾸는 메서드를 두지 않는다 — 변경 감지는 두 칸을 모두 쓰는 UPDATE 를 내보내 같은 회원의 동시 요청이 바꾼 다른 항목을 되돌린다. 기존 행은
     * 저장소의 부분 갱신 쿼리(MemberNotificationSettingRepository#changeByMemberId)로만 고친다.
     */

    /**
     * 새 행인지 판정한다 — {@code MemberRegionEntity#isNew} 와 같은 이유(Snowflake ID 를 미리 정해 기본 판정이 merge 로 가면 겹치는 ID 를 조용히
     * 덮어쓴다)로 감사 컬럼 {@code createdAt} 이 비어 있으면 새 행이다 (coding-conventions §8-1).
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
