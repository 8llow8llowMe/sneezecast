package com.sneezecast.domainlayer.official.adapter.out.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link OfficialSourceSnapshotEntity} 가 만드는 스키마가 entity-design §3-3 과 같은 구조인지 본다. 이 테이블은 batch-service 가 JDBC
 * 로 쓴다. 컨텍스트 구성은 {@code DistrictEntitySchemaTest} 와 같다 (dev 프로파일, H2, 외부 연결 없음).
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-official-snapshot-schema;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-surveillance-official-snapshot-schema-test-access-key-0123456789",
    "REPORTER_KEY_PEPPER=sneezecast-official-snapshot-schema-test-pepper-0123456789"
})
class OfficialSourceSnapshotEntitySchemaTest {

    private static final String TABLE = "OFFICIAL_SOURCE_SNAPSHOT";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("official_source_snapshot 의 컬럼과 NULL 여부가 설계와 같다 — content_sha256 · error_code 만 nullable")
    void columnsMatchDesign() {
        Map<String, String> nullable = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '" + TABLE + "'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(), row -> row.get("IS_NULLABLE").toString()));

        assertThat(nullable).containsOnlyKeys(
            "id", "source", "program", "request_key", "channel", "content_sha256", "byte_length", "row_count",
            "imported_count", "status", "error_code", "run_started_at", "created_at", "updated_at");
        assertThat(nullable).containsEntry("content_sha256", "YES").containsEntry("error_code", "YES");
        nullable.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("content_sha256") && !entry.getKey().equals("error_code"))
            .forEach(entry -> assertThat(entry.getValue()).as(entry.getKey()).isEqualTo("NO"));
    }

    @Test
    @DisplayName("길이 · 타입이 설계와 같다 — enum 컬럼은 VARCHAR, content_sha256 은 CHAR(64)")
    void columnTypesMatchDesign() {
        Map<String, Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '"
                    + TABLE + "'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(), row -> row));

        Map<String, Integer> varchars = Map.of(
            "source", 30, "program", 20, "request_key", 200, "channel", 20, "status", 20, "error_code", 50);
        varchars.forEach((column, length) -> {
            assertThat(dataType(columns, column)).as(column).isEqualTo("CHARACTER VARYING");
            assertThat(((Number) columns.get(column).get("CHARACTER_MAXIMUM_LENGTH")).intValue()).as(column).isEqualTo(length);
        });

        assertThat(dataType(columns, "content_sha256")).isEqualTo("CHARACTER");
        assertThat(((Number) columns.get("content_sha256").get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isEqualTo(64);
        assertThat(dataType(columns, "id")).isEqualTo("BIGINT");
        assertThat(dataType(columns, "byte_length")).isEqualTo("INTEGER");
        assertThat(dataType(columns, "row_count")).isEqualTo("INTEGER");
        assertThat(dataType(columns, "imported_count")).isEqualTo("INTEGER");
        assertThat(dataType(columns, "run_started_at")).startsWith("TIMESTAMP");
    }

    @Test
    @DisplayName("request_key, created_at 에 idx_official_source_snapshot_request_key_created_at 이 있다")
    void indexesMatchDesign() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT i.INDEX_NAME, i.INDEX_TYPE_NAME, c.COLUMN_NAME
            FROM INFORMATION_SCHEMA.INDEXES i
            JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
              ON c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME
            WHERE UPPER(i.TABLE_NAME) = 'OFFICIAL_SOURCE_SNAPSHOT'
            ORDER BY c.ORDINAL_POSITION""");

        List<String> columns = indexes.stream()
            .filter(row -> row.get("INDEX_NAME").toString().toLowerCase().startsWith("idx_official_source_snapshot_request_key_created_at"))
            .map(row -> row.get("COLUMN_NAME").toString().toLowerCase())
            .toList();
        assertThat(columns).containsExactly("request_key", "created_at");
        assertThat(indexes.stream()
            .filter(row -> row.get("INDEX_NAME").toString().toLowerCase().startsWith("idx_official_source_snapshot_request_key_created_at"))
            .map(row -> row.get("INDEX_TYPE_NAME").toString())
            .findFirst()
            .orElse("")).doesNotContain("UNIQUE");
    }

    private static String dataType(Map<String, Map<String, Object>> columns, String column) {
        return columns.get(column).get("DATA_TYPE").toString();
    }
}
