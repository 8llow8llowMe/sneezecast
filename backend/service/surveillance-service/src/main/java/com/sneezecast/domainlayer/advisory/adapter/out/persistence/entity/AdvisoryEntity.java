package com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.advisory.domain.enums.AdvisoryStatus;
import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
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
 * 예방 안내 (entity-design §2-3). 회원 · 보고자 컬럼이 없다 — 처리한 운영자는 이력({@code advisory_history.operator_id})에만 남는다.
 *
 * <p>근거 집계 · 동네 · 주 · 인용 수치({@code cited_*})는 초안 생성 때 정해지고 바뀌지 않는다 — 매핑도 {@code updatable = false} 라 UPDATE 에 실리지 않는다.
 * 고칠 수 있는 것은 {@link #applyState} 의 값뿐이고, 수정은 조회한 엔티티를 바꿔 변경 감지로 한다 — {@code version}(@Version)이 동시 수정을 막는다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "advisory",
    indexes = {
        // 공개 목록 — 동네의 발행 안내 (#215)
        @Index(name = "idx_advisory_district_code_status_published_at", columnList = "districtCode, status, publishedAt"),
        @Index(name = "idx_advisory_aggregate_id", columnList = "aggregateId"),
        // 정정 발행이 잇는 이전 안내 → 정정한 안내 (#267 정정됨 판정)
        @Index(name = "idx_advisory_corrects_advisory_id", columnList = "correctsAdvisoryId")
    })
public class AdvisoryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Comment("안내 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, updatable = false, length = 8)
    @Comment("대상 행정동 코드 (FK: district.code)")
    private String districtCode;

    @Column(nullable = false, updatable = false, length = 8, columnDefinition = "CHAR(8)")
    @Comment("근거 집계 주 YYYY-Www (ISO 주, 월요일 시작, KST)")
    private String isoWeek;

    @Column(nullable = false, updatable = false)
    @Comment("근거 집계 아이디 (FK: district_weekly_aggregate.id)")
    private Long aggregateId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("상태 (AdvisoryStatus)")
    private AdvisoryStatus status;

    @Column(nullable = false, length = 100)
    @Comment("제목 (현재본)")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Comment("본문 (현재본)")
    private String body;

    @Column(nullable = false, updatable = false)
    @Comment("인용 참여자 수 - 초안 생성 때 집계에서 복사해 고정")
    private int citedParticipantCount;

    @Column(nullable = false, updatable = false)
    @Comment("인용 증상 보고 수")
    private int citedSymptomaticCount;

    @Column(nullable = false, updatable = false)
    @Comment("인용 호흡기 증상군 보고 수")
    private int citedRespiratoryCount;

    @Column(nullable = false, updatable = false)
    @Comment("인용 장관 증상군 보고 수")
    private int citedEntericCount;

    @Column(nullable = false, updatable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("인용 단계 (AggregateLevel). INSUFFICIENT 는 들어갈 수 없다")
    private AggregateLevel citedLevel;

    @Column(nullable = false, updatable = false, length = 20)
    @Comment("인용 집계의 판정 규칙 버전")
    private String citedRuleVersion;

    @Comment("이 안내가 정정한 이전 안내 아이디 (FK: advisory.id). 정정 발행이 아니면 null")
    private Long correctsAdvisoryId;

    @Column(columnDefinition = "TIMESTAMP")
    @Comment("발행 시각")
    private LocalDateTime publishedAt;

    @Column(columnDefinition = "TIMESTAMP")
    @Comment("철회 시각")
    private LocalDateTime retractedAt;

    @Version
    @Column(nullable = false)
    @Comment("낙관적 락 버전 - 운영자 두 명의 동시 수정에서 한쪽 수정이 사라지지 않게 한다")
    private Long version;

    /** 상태 전이 · 수정 결과를 반영한다. 근거 집계 · 인용 수치 · 동네 · 주는 고치지 않는다. 버전은 flush 때 Hibernate 가 올린다. */
    public void applyState(AdvisoryStatus status, String title, String body, LocalDateTime publishedAt, LocalDateTime retractedAt) {
        this.status = status;
        this.title = title;
        this.body = body;
        this.publishedAt = publishedAt;
        this.retractedAt = retractedAt;
    }

    /**
     * 새 행인지 판정한다 — {@code createdAt} 은 persist 때 Auditing 이 채우므로 비어 있으면 아직 저장되지 않은 엔티티다 (coding-conventions §8-1).
     * Snowflake 로 ID 를 미리 정하므로 기본 판정이면 {@code save} 가 merge 로 가서 겹치는 ID 의 행을 조용히 덮어쓴다.
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
