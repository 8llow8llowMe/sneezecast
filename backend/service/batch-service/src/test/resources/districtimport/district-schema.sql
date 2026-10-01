-- district 테이블 (테스트용, H2 MODE=MySQL).
-- 정본은 surveillance-service 의 DistrictEntity(JPA)다. 엔티티 컬럼 · 인덱스가 바뀌면 이 파일과 JdbcDistrictBulkAdapter 를 같이 고친다.
CREATE TABLE district (
    id              BIGINT      NOT NULL,
    code            VARCHAR(8)  NOT NULL,
    name            VARCHAR(50) NOT NULL,
    sido_code       VARCHAR(2)  NOT NULL,
    sido_name       VARCHAR(30) NOT NULL,
    sigungu_code    VARCHAR(5)  NOT NULL,
    sigungu_name    VARCHAR(30) NOT NULL,
    valid_from_year SMALLINT    NOT NULL,
    valid_to_year   SMALLINT,
    last_seen_year  SMALLINT    NOT NULL,
    synced_at       TIMESTAMP   NOT NULL,
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_district_code UNIQUE (code)
);

CREATE INDEX idx_district_sigungu_code ON district (sigungu_code);
