-- official_surveillance · official_source_snapshot 테이블 (테스트용, H2 MODE=MySQL).
-- 정본은 surveillance-service 의 OfficialSurveillanceEntity · OfficialSourceSnapshotEntity(JPA)다.
-- 엔티티 컬럼 · 인덱스가 바뀌면 이 파일과 JdbcOfficialSurveillanceBulkAdapter · JdbcOfficialSourceSnapshotBulkAdapter 의 SQL 을 같이 고친다.
-- 값 컬럼이 value 가 아니라 metric_value 인 이유: value 는 H2 예약어다.
CREATE TABLE official_surveillance (
    id                 BIGINT         NOT NULL,
    source             VARCHAR(30)    NOT NULL,
    program            VARCHAR(20)    NOT NULL,
    disease_key        VARCHAR(100)   NOT NULL,
    disease_name       VARCHAR(100)   NOT NULL,
    disease_group      VARCHAR(20),
    metric             VARCHAR(30)    NOT NULL,
    age_group          VARCHAR(20)    NOT NULL,
    region_level       VARCHAR(10)    NOT NULL,
    region_code        VARCHAR(4)     NOT NULL,
    region_name        VARCHAR(30)    NOT NULL,
    period_type        VARCHAR(10)    NOT NULL,
    period_year        SMALLINT       NOT NULL,
    period_week        TINYINT        NOT NULL,
    period_start       DATE           NOT NULL,
    period_end         DATE           NOT NULL,
    metric_value       DECIMAL(12, 2),
    source_snapshot_id BIGINT         NOT NULL,
    synced_at          TIMESTAMP      NOT NULL,
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_official_surveillance_natural_key UNIQUE (
        source, program, disease_key, metric, age_group, region_level, region_code, period_type, period_year, period_week
    )
);

CREATE INDEX idx_official_surveillance_program_period_start ON official_surveillance (program, period_start);
CREATE INDEX idx_official_surveillance_source_snapshot_id ON official_surveillance (source_snapshot_id);

CREATE TABLE official_source_snapshot (
    id             BIGINT       NOT NULL,
    source         VARCHAR(30)  NOT NULL,
    program        VARCHAR(20)  NOT NULL,
    request_key    VARCHAR(200) NOT NULL,
    channel        VARCHAR(20)  NOT NULL,
    content_sha256 CHAR(64),
    byte_length    INT          NOT NULL,
    row_count      INT          NOT NULL,
    imported_count INT          NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    error_code     VARCHAR(50),
    run_started_at TIMESTAMP    NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_official_source_snapshot_request_key_created_at ON official_source_snapshot (request_key, created_at);
