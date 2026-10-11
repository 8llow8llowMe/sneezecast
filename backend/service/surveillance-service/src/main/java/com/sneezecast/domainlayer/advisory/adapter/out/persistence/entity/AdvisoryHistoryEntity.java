package com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 예방 안내 상태 전이 · 수정 이력 (entity-design §2-4). 쌓기만 하고 고치지 않는다 — 고치는 메서드가 없다. {@code created_at} 이 전이 시각이다.
 *
 * <p>{@code operator_id} 는 surveillance 가 회원 ID 를 갖는 유일한 예외다(운영자 감사, modules.md · entity-design §2).
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "advisory_history",
    indexes = @Index(name = "idx_advisory_history_advisory_id", columnList = "advisoryId"))
public class AdvisoryHistoryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Comment("이력 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false)
    @Comment("안내 아이디 (FK: advisory.id)")
    private Long advisoryId;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("이전 상태 (AdvisoryStatus). 생성이면 null")
    private AdvisoryStatus fromStatus;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("바뀐 상태 (AdvisoryStatus)")
    private AdvisoryStatus toStatus;

    @Column(nullable = false, length = 100)
    @Comment("이 시점의 제목")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Comment("이 시점의 본문 - 무엇이 승인 · 발행됐는지 복원한다")
    private String body;

    @Column(nullable = false)
    @Comment("요청한 운영자 아이디 (FK: auth member.id). 운영자 감사 컬럼")
    private Long operatorId;

    @Column(length = 60)
    @Comment("AI 초안을 만든 모델 id. AI_DRAFTED 전이에만")
    private String aiModel;

    @Column(length = 20)
    @Comment("AI 초안에 쓴 프롬프트 버전. AI_DRAFTED 전이에만")
    private String promptVersion;

    @Column(length = 500)
    @Comment("수정 · 철회 사유")
    private String memo;

    /** {@code AdvisoryEntity#isNew} 와 같은 판정 (coding-conventions §8-1). */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
