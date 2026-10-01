package com.sneezecast.domainlayer.district.adapter.out.persistence.entity;

import com.sneezecast.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

/**
 * 행정동 마스터 (SGIS 읍면동, entity-design §3-1).
 *
 * <p><b>이 엔티티가 테이블 구조의 정본이다.</b> 행은 batch-service 의 {@code districtImportJob} 이 JDBC 로 쓰고({@code JdbcDistrictBulkAdapter}),
 * surveillance 는 읽기만 한다. 컬럼을 바꾸면 batch 의 upsert SQL 과 테스트 DDL({@code districtimport/district-schema.sql})을 같이 고친다.
 *
 * <p>{@code id} 는 생성하지 않고 할당한다 — batch 가 SGIS 코드를 숫자로 바꿔 결정적으로 만든다({@code Long.parseLong(code)}). batch 는
 * Snowflake 를 쓰지 않고, 같은 코드는 몇 번을 적재해도 같은 id 여야 upsert 가 한 행을 가리킨다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "district",
    indexes = {
        @Index(name = "uk_district_code", columnList = "code", unique = true),
        @Index(name = "idx_district_sigungu_code", columnList = "sigunguCode")
    }
)
public class DistrictEntity extends BaseEntity {

    @Id
    @Comment("행정동 아이디 (SGIS 읍면동 코드를 숫자로 바꾼 값, batch 가 할당)")
    private Long id;

    @Column(nullable = false, length = 8)
    @Comment("SGIS 읍면동 코드 8자리 (행안부 10자리 코드와 체계가 다르다)")
    private String code;

    @Column(nullable = false, length = 50)
    @Comment("읍면동 이름 (SGIS adm_nm 마지막 토큰)")
    private String name;

    @Column(nullable = false, length = 2)
    @Comment("SGIS 시도 코드 (code 앞 2자리)")
    private String sidoCode;

    @Column(nullable = false, length = 30)
    @Comment("시도 이름")
    private String sidoName;

    @Column(nullable = false, length = 5)
    @Comment("SGIS 시군구 코드 (code 앞 5자리)")
    private String sigunguCode;

    @Column(nullable = false, length = 30)
    @Comment("시군구 이름")
    private String sigunguName;

    @Column(nullable = false)
    @Comment("처음 확인된 SGIS 기준 연도 (실제 신설 연도가 아니다)")
    private short validFromYear;

    @Comment("폐지 직전 SGIS 기준 연도. null 이면 현행")
    private Short validToYear;

    @Column(nullable = false)
    @Comment("이 코드가 마지막으로 확인된 SGIS 기준 연도")
    private short lastSeenYear;

    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    @Comment("마지막 적재 시각")
    private LocalDateTime syncedAt;
}
