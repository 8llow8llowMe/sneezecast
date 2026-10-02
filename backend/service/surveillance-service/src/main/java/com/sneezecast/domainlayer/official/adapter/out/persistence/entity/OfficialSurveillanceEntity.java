package com.sneezecast.domainlayer.official.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 질병관리청 감시 자료 (전수신고 · 표본감시, entity-design §3-2).
 *
 * <p><b>이 엔티티가 테이블 구조의 정본이다.</b> 행은 batch-service 가 JDBC upsert 로 쓰고, surveillance 는 읽기만 한다. 컬럼을 바꾸면
 * batch 의 upsert SQL 과 테스트 DDL 도 같이 고친다. 시민 자가보고와 섞지 않는다 — 응답도 별도 필드로 내리고 출처 · 집계 단위 ·
 * 기준 기간 · 수집 시각을 싣는다.
 *
 * <p>{@code id} 는 생성하지 않고 batch 가 Snowflake 로 할당한다. 멱등 키({@code uk_official_surveillance_natural_key})에는 원천이 준
 * 정수 연도 · 주차만 넣고 가공한 날짜({@code periodStart} · {@code periodEnd})는 넣지 않는다 — 주차 → 날짜 규칙이 바뀌어도 행이
 * 겹치지 않게 하기 위해서다.
 *
 * <p>{@code Persistable} 을 구현하지 않는다. surveillance 는 이 테이블을 {@code save} 하지 않아 isNew 판정이 쓰이지 않는다 (판정은
 * save 경로에서만 의미가 있다). #74 가 BaseEntity 로 올리면 함께 적용된다.
 *
 * <p>enum 컬럼에 {@code @JdbcTypeCode(SqlTypes.VARCHAR)} 를 함께 건다. 없으면 Hibernate 6 이 MySQL · H2 에서 네이티브
 * {@code enum(...)} 을 만들어 값을 늘릴 때마다 ALTER 가 필요하다. 저장 값(이름)은 batch 와의 DB 계약이다.
 *
 * <p>값 컬럼은 {@code value} 가 아니라 {@code metric_value} 다. {@code VALUE} 는 H2 예약어라 batch · surveillance 의 H2 테스트와
 * upsert SQL 이 모두 인용이나 {@code NON_KEYWORDS} 를 달고 다녀야 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "official_surveillance",
    indexes = {
        @Index(
            name = "uk_official_surveillance_natural_key",
            columnList = "source, program, diseaseKey, metric, ageGroup, regionLevel, regionCode, periodType, periodYear, periodWeek",
            unique = true),
        @Index(name = "idx_official_surveillance_program_period_start", columnList = "program, periodStart"),
        @Index(name = "idx_official_surveillance_source_snapshot_id", columnList = "sourceSnapshotId")
    }
)
public class OfficialSurveillanceEntity extends BaseEntity {

    @Id
    @Comment("질병관리청 감시 자료 아이디 (batch 가 Snowflake 로 할당)")
    private Long id;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("원천 (OfficialSource)")
    private OfficialSource source;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("감시 프로그램 (OfficialProgram)")
    private OfficialProgram program;

    @Column(nullable = false, length = 100)
    @Comment("정규화 키 (표본감시는 포털 병원체 코드, 전수신고는 감염병명에서 앞의 @ 표식을 뗀 값)")
    private String diseaseKey;

    @Column(nullable = false, length = 100)
    @Comment("표시 이름 (원천 그대로)")
    private String diseaseName;

    @Column(length = 20)
    @Comment("원천 분류 (전수신고는 제N급, 표본감시는 세균 / 바이러스 / 원충). null 가능")
    private String diseaseGroup;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("값의 종류 (OfficialMetric)")
    private OfficialMetric metric;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("연령대 (OfficialAgeGroup, 기본 ALL)")
    private OfficialAgeGroup ageGroup;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("지역 단위 (OfficialRegionLevel)")
    private OfficialRegionLevel regionLevel;

    @Column(nullable = false, length = 4)
    @Comment("질병관리청 시도 코드 (SGIS 코드와 다르다). 전국이면 00")
    private String regionCode;

    @Column(nullable = false, length = 30)
    @Comment("지역 이름 (전국이면 전국)")
    private String regionName;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("기간 단위 (OfficialPeriodType)")
    private OfficialPeriodType periodType;

    @Column(nullable = false)
    @Comment("원천 연도")
    private short periodYear;

    @Column(nullable = false)
    @Comment("원천 주차 (질병관리청 주차 그대로). 연 단위면 0")
    private byte periodWeek;

    @Column(nullable = false)
    @Comment("기간 시작일 (가공)")
    private LocalDate periodStart;

    @Column(nullable = false)
    @Comment("기간 종료일 (가공)")
    private LocalDate periodEnd;

    @Column(precision = 12, scale = 2)
    @Comment("값 (metric 의 단위). 원천이 빈 칸이면 null (0 과 구분한다)")
    private BigDecimal metricValue;

    @Column(nullable = false)
    @Comment("마지막으로 이 값을 쓴 실행 (FK: official_source_snapshot.id)")
    private Long sourceSnapshotId;

    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    @Comment("마지막 적재 시각 (응답 · 화면의 수집 시각)")
    private LocalDateTime syncedAt;
}
