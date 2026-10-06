package com.sneezecast.domainlayer.member.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * surveillance 원시 보고 파기 요청 (entity-design §1-5). Feign 호출 한 번에 기대지 않고 완료될 때까지 다시 부르려고 행으로 남긴다.
 * 회원은 raw FK 로만 참조하고 DB FK 제약을 두지 않는다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "report_purge_request",
    indexes = {
        // 회원의 미완료 요청 확인 (report:write 발급 판정 · 중복 요청 방지)
        @Index(name = "idx_report_purge_request_member_id", columnList = "memberId"),
        // 스케줄러의 미완료 행 조회 (completed_at is null)
        @Index(name = "idx_report_purge_request_completed_at", columnList = "completedAt")
    })
public class ReportPurgeRequestEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Comment("파기 요청 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false)
    @Comment("회원 아이디 (FK: member.id)")
    private Long memberId;

    @Column(length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("파기 사유 (WITHDRAWAL/HEALTH_CONSENT_WITHDRAWN)")
    private PurgeReason reason;

    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    @Comment("요청 시각 - 탈퇴 · 철회 시각. 완료 판정(요청 시각 + access TTL + 여유 이후 호출 성공)의 기준")
    private LocalDateTime requestedAt;

    @Column(columnDefinition = "TIMESTAMP")
    @Comment("첫 파기 성공 시각")
    private LocalDateTime firstPurgedAt;

    @Column(columnDefinition = "TIMESTAMP")
    @Comment("완료 시각 - null 이면 미완료")
    private LocalDateTime completedAt;

    @Column(nullable = false)
    @ColumnDefault("0")
    @Comment("파기 호출 시도 횟수")
    private int attemptCount;

    @Column(length = 200)
    @Comment("마지막 실패 사유 - 예외 코드 · 상태 코드만 (응답 본문을 넣지 않는다)")
    private String lastError;

    /**
     * 새 행인지 판정한다 — 감사 컬럼 {@code createdAt} 이 비어 있으면 아직 저장되지 않은 엔티티다. Snowflake 로 ID 를 미리 정하므로 이 판정이 없으면
     * {@code save} 가 merge 로 가서 겹치는 ID 를 조용히 덮어쓴다({@link MemberConsentEntity#isNew()} 와 같은 이유).
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
