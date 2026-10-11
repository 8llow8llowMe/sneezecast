package com.sneezecast.domainlayer.report.adapter.out.persistence.entity;

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
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

/**
 * 주간 건강 보고 원시 행 (entity-design §2-1). 민감정보(건강)라 이 테이블 밖으로 행을 내보내는 API 는 없다 — 조회는 본인 보고(이번 주 · 지난 보고 내역)와 집계뿐이다.
 *
 * <p><b>{@code member_id} 컬럼을 두지 않는다.</b> 보고자는 {@code reporter_key} 로만 식별한다 (architecture-guide §6). 개별 증상 · 자유 서술 ·
 * 좌표 컬럼도 만들지 않는다.
 *
 * <p>{@code (reporter_key, iso_week)} unique 가 "같은 사람의 같은 주는 한 번만 센다" 는 불변식을 DB 에서 지킨다. 수정은 이 행을 고치고
 * ({@code WeeklyReportRepository#updateCurrent}), 취소는 행을 지운다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "weekly_report",
    indexes = {
        @Index(name = WeeklyReportEntity.REPORTER_KEY_ISO_WEEK_UNIQUE_INDEX, columnList = "reporterKey, isoWeek", unique = true),
        // 집계 스케줄러의 주 · 행정동 GROUP BY
        @Index(name = "idx_weekly_report_iso_week_district_code", columnList = "isoWeek, districtCode")
    })
public class WeeklyReportEntity extends BaseEntity {

    /** 동시 제출 판정이 이 이름으로 제약 위반을 가린다 ({@code WeeklyReportRepositoryAdapter#insert}). */
    public static final String REPORTER_KEY_ISO_WEEK_UNIQUE_INDEX = "uk_weekly_report_reporter_key_iso_week";

    /** {@code revision_count}(SMALLINT) 상한. 수정 쿼리가 여기서 증가를 멈춘다 — 넘기면 범위 초과로 수정 요청이 500 이 된다. */
    public static final short MAX_REVISION_COUNT = Short.MAX_VALUE;

    @Id
    @Comment("보고 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    @Comment("가명 보고자 키 - HMAC-SHA256(pepper, memberId) 소문자 hex. 회원 아이디는 저장하지 않는다")
    private String reporterKey;

    @Column(nullable = false, length = 8, columnDefinition = "CHAR(8)")
    @Comment("보고 주 YYYY-Www (ISO 주, 월요일 시작, KST). 서버가 요청 시각으로 정한다")
    private String isoWeek;

    @Column(nullable = false, length = 8)
    @Comment("보고 행정동 코드 (FK: district.code). 같은 주 수정이면 마지막 요청 값")
    private String districtCode;

    @Column(nullable = false)
    @Comment("증상군 비트 (SymptomGroup - RESPIRATORY 1, ENTERIC 2). 0 = 증상 없음")
    private byte symptomMask;

    @Column(nullable = false)
    @ColumnDefault("0")
    @Comment("같은 주 수정 횟수. 반복 보고 검토 후보의 근거")
    private short revisionCount;
}
