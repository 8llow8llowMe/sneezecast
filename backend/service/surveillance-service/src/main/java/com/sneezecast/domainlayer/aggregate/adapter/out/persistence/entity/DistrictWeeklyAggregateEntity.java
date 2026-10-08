package com.sneezecast.domainlayer.aggregate.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.aggregate.domain.enums.AggregateLevel;
import com.sneezecast.domainlayer.aggregate.domain.enums.InsufficientReason;
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
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 행정동 × 주 집계 (entity-design §2-2). 익명 집계라 회원 · 보고자 컬럼이 없고, 원시 보고가 지워져도 남는다.
 *
 * <p>{@code (district_code, iso_week)} unique 가 한 칸 한 행을 지킨다. 재계산은 원시 보고를 다시 세서 이 행을 덮어쓰고(멱등), 마감된 행
 * ({@code finalized_at} not null)은 더 고치지 않는다 — 갱신 쿼리가 조건으로 막는다 ({@code DistrictWeeklyAggregateRepository}).
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "district_weekly_aggregate",
    indexes = {
        @Index(name = DistrictWeeklyAggregateEntity.DISTRICT_CODE_ISO_WEEK_UNIQUE_INDEX, columnList = "districtCode, isoWeek", unique = true),
        // 주 단위 전체 조회 · 기준선 주 조회 · 마감
        @Index(name = "idx_district_weekly_aggregate_iso_week", columnList = "isoWeek")
    })
public class DistrictWeeklyAggregateEntity extends BaseEntity implements Persistable<Long> {

    public static final String DISTRICT_CODE_ISO_WEEK_UNIQUE_INDEX = "uk_district_weekly_aggregate_district_code_iso_week";

    @Id
    @Comment("집계 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, length = 8)
    @Comment("행정동 코드 (FK: district.code)")
    private String districtCode;

    @Column(nullable = false, length = 8, columnDefinition = "CHAR(8)")
    @Comment("집계 주 YYYY-Www (ISO 주, 월요일 시작, KST)")
    private String isoWeek;

    @Column(nullable = false)
    @Comment("참여자 수 - 그 주 보고 행 수 (증상 없음 포함). 비율의 분모")
    private int participantCount;

    @Column(nullable = false)
    @Comment("증상군 하나 이상 보고 수")
    private int symptomaticCount;

    @Column(nullable = false)
    @Comment("호흡기 증상군 보고 수")
    private int respiratoryCount;

    @Column(nullable = false)
    @Comment("장관 증상군 보고 수")
    private int entericCount;

    @Column(nullable = false)
    @Comment("그 주에 한 번 이상 수정된 보고 수. 반복 보고 검토 후보의 근거")
    private int revisedReportCount;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("단계 (AggregateLevel)")
    private AggregateLevel level;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("자료 부족 이유 (InsufficientReason). level 이 INSUFFICIENT 일 때만")
    private InsufficientReason insufficientReason;

    @Comment("판정에 쓴 기준선 주들의 참여자 합. 기준선이 없으면 null")
    private Integer baselineParticipantCount;

    @Comment("판정에 쓴 기준선 주들의 증상 보고 합. 기준선이 없으면 null")
    private Integer baselineSymptomaticCount;

    @Column(nullable = false, length = 20)
    @Comment("판정 규칙 버전 (설정 aggregate.rule-version)")
    private String ruleVersion;

    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    @Comment("마지막 재계산 시각")
    private LocalDateTime calculatedAt;

    @Column(columnDefinition = "TIMESTAMP")
    @Comment("주 마감 시각. 마감 후에는 재계산하지 않는다")
    private LocalDateTime finalizedAt;

    /**
     * 새 행인지 판정한다 — {@code createdAt} 은 persist 때 Auditing 이 채우므로 비어 있으면 아직 저장되지 않은 엔티티다 (coding-conventions §8-1).
     * Snowflake 로 ID 를 미리 정하므로 기본 판정이면 {@code save} 가 merge 로 가서 겹치는 ID 의 행을 조용히 덮어쓴다. 기존 행 수정은 갱신 쿼리로 한다.
     */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
