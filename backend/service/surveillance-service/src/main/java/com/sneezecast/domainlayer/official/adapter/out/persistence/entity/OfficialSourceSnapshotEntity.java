package com.sneezecast.domainlayer.official.adapter.out.persistence.entity;

import com.sneezecast.domainlayer.official.domain.enums.IngestChannel;
import com.sneezecast.domainlayer.official.domain.enums.IngestStatus;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 외부 원천 적재 이력 (entity-design §3-3).
 *
 * <p><b>이 엔티티가 테이블 구조의 정본이다.</b> 행은 batch-service 가 JDBC 로 쓰고, surveillance 는 읽기만 한다. 컬럼을 바꾸면 batch 의
 * SQL 과 테스트 DDL 도 같이 고친다.
 *
 * <p>실행 한 번(조회 조건 하나)에 한 행이다. <b>수정하지 않고 쌓기만 한다.</b> {@code FAILED} 행은 원천 데이터를 쓰지 않으므로 실패해도
 * {@code official_surveillance} 의 기존 값은 그대로다.
 *
 * <p>{@code id} 는 batch 가 Snowflake 로 할당한다. {@code Persistable} 을 구현하지 않는다 — surveillance 는 이 테이블을 {@code save}
 * 하지 않아 isNew 판정이 쓰이지 않는다. #74 가 BaseEntity 로 올리면 함께 적용된다.
 *
 * <p>enum 컬럼은 {@code @JdbcTypeCode(SqlTypes.VARCHAR)} 로 네이티브 enum 타입을 막는다 ({@link OfficialSurveillanceEntity} 참고).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "official_source_snapshot",
    indexes = {
        @Index(name = "idx_official_source_snapshot_request_key_created_at", columnList = "requestKey, createdAt")
    }
)
public class OfficialSourceSnapshotEntity extends BaseEntity {

    @Id
    @Comment("적재 이력 아이디 (batch 가 Snowflake 로 할당)")
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

    @Column(nullable = false, length = 200)
    @Comment("조회 조건 요약 (예: ari:2026-31~2026-38:age=ALL)")
    private String requestKey;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("수집 경로 (IngestChannel)")
    private IngestChannel channel;

    @Column(length = 64, columnDefinition = "CHAR(64)")
    @Comment("받은 본문의 SHA-256 해시 (추적용). 실패해서 본문이 없으면 null")
    private String contentSha256;

    @Column(nullable = false)
    @Comment("받은 바이트 수")
    private int byteLength;

    @Column(nullable = false)
    @Comment("파싱한 원천 행 수")
    private int rowCount;

    @Column(nullable = false)
    @Comment("upsert 한 행 수")
    private int importedCount;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Comment("적재 결과 (IngestStatus)")
    private IngestStatus status;

    @Column(length = 50)
    @Comment("실패 코드 (잡 ErrorCode 의 code 값, 예: OFFICIAL_INGEST_003). 성공이면 null")
    private String errorCode;

    @Column(nullable = false, columnDefinition = "TIMESTAMP")
    @Comment("실행 시작 시각")
    private LocalDateTime runStartedAt;
}
