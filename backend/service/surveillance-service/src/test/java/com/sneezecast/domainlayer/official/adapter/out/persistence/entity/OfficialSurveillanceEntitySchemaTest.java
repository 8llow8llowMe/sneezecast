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
 * {@link OfficialSurveillanceEntity} 가 만드는 스키마가 entity-design §3-2 와 batch 의 upsert SQL 이 전제하는 구조인지 본다.
 *
 * <p>batch-service 는 이 테이블을 JDBC 로 쓰고 멱등은 {@code uk_official_surveillance_natural_key} 에 기댄다. 엔티티가 바뀌면 여기서
 * 먼저 깨진다. 컨텍스트 구성은 {@code DistrictEntitySchemaTest} 와 같다 (dev 프로파일, H2, 외부 연결 없음).
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-official-surveillance-schema;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-surveillance-official-surveillance-schema-test-access-key-0123456789",
    "REPORTER_KEY_PEPPER=sneezecast-official-surveillance-schema-test-pepper-0123456789"
})
class OfficialSurveillanceEntitySchemaTest {

    private static final String TABLE = "OFFICIAL_SURVEILLANCE";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("official_surveillance 의 컬럼과 NULL 여부가 설계와 같다 — disease_group · metric_value 만 nullable")
    void columnsMatchDesign() {
        Map<String, String> nullable = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '" + TABLE + "'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(), row -> row.get("IS_NULLABLE").toString()));

        assertThat(nullable).containsOnlyKeys(
            "id", "source", "program", "disease_key", "disease_name", "disease_group", "metric", "age_group",
            "region_level", "region_code", "region_name", "period_type", "period_year", "period_week",
            "period_start", "period_end", "metric_value", "source_snapshot_id", "synced_at", "created_at", "updated_at");
        assertThat(nullable).containsEntry("disease_group", "YES").containsEntry("metric_value", "YES");
        nullable.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("disease_group") && !entry.getKey().equals("metric_value"))
            .forEach(entry -> assertThat(entry.getValue()).as(entry.getKey()).isEqualTo("NO"));
    }

    @Test
    @DisplayName("길이 · 타입이 설계와 같다 — enum 컬럼은 네이티브 enum 이 아닌 VARCHAR, metric_value 는 DECIMAL(12,2)")
    void columnTypesMatchDesign() {
        Map<String, Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE "
                    + "FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '" + TABLE + "'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(), row -> row));

        Map<String, Integer> varchars = Map.ofEntries(
            Map.entry("source", 30), Map.entry("program", 20), Map.entry("disease_key", 100), Map.entry("disease_name", 100),
            Map.entry("disease_group", 20), Map.entry("metric", 30), Map.entry("age_group", 20), Map.entry("region_level", 10),
            Map.entry("region_code", 4), Map.entry("region_name", 30), Map.entry("period_type", 10));
        varchars.forEach((column, length) -> {
            assertThat(dataType(columns, column)).as(column).isEqualTo("CHARACTER VARYING");
            assertThat(((Number) columns.get(column).get("CHARACTER_MAXIMUM_LENGTH")).intValue()).as(column).isEqualTo(length);
        });

        assertThat(dataType(columns, "id")).isEqualTo("BIGINT");
        assertThat(dataType(columns, "source_snapshot_id")).isEqualTo("BIGINT");
        assertThat(dataType(columns, "period_year")).isEqualTo("SMALLINT");
        assertThat(dataType(columns, "period_week")).isEqualTo("TINYINT");
        assertThat(dataType(columns, "period_start")).isEqualTo("DATE");
        assertThat(dataType(columns, "period_end")).isEqualTo("DATE");
        assertThat(dataType(columns, "metric_value")).isIn("NUMERIC", "DECIMAL");
        assertThat(((Number) columns.get("metric_value").get("NUMERIC_PRECISION")).intValue()).isEqualTo(12);
        assertThat(((Number) columns.get("metric_value").get("NUMERIC_SCALE")).intValue()).isEqualTo(2);
        assertThat(dataType(columns, "synced_at")).startsWith("TIMESTAMP");
    }

    @Test
    @DisplayName("자연키 유니크와 일반 인덱스 두 개가 설계의 이름 · 컬럼 순서로 있다")
    void indexesMatchDesign() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT i.INDEX_NAME, i.INDEX_TYPE_NAME, c.COLUMN_NAME
            FROM INFORMATION_SCHEMA.INDEXES i
            JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
              ON c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME
            WHERE UPPER(i.TABLE_NAME) = 'OFFICIAL_SURVEILLANCE'
            ORDER BY c.ORDINAL_POSITION""");

        assertThat(indexColumns(indexes, "uk_official_surveillance_natural_key")).containsExactly(
            "source", "program", "disease_key", "metric", "age_group", "region_level", "region_code",
            "period_type", "period_year", "period_week");
        assertThat(indexType(indexes, "uk_official_surveillance_natural_key")).contains("UNIQUE");
        assertThat(indexColumns(indexes, "idx_official_surveillance_program_period_start")).containsExactly("program", "period_start");
        assertThat(indexType(indexes, "idx_official_surveillance_program_period_start")).doesNotContain("UNIQUE");
        assertThat(indexColumns(indexes, "idx_official_surveillance_source_snapshot_id")).containsExactly("source_snapshot_id");
        assertThat(indexType(indexes, "idx_official_surveillance_source_snapshot_id")).doesNotContain("UNIQUE");
    }

    private static String dataType(Map<String, Map<String, Object>> columns, String column) {
        return columns.get(column).get("DATA_TYPE").toString();
    }

    private static List<String> indexColumns(List<Map<String, Object>> indexes, String indexName) {
        return indexes.stream()
            .filter(row -> row.get("INDEX_NAME").toString().toLowerCase().startsWith(indexName))
            .map(row -> row.get("COLUMN_NAME").toString().toLowerCase())
            .toList();
    }

    private static String indexType(List<Map<String, Object>> indexes, String indexName) {
        return indexes.stream()
            .filter(row -> row.get("INDEX_NAME").toString().toLowerCase().startsWith(indexName))
            .map(row -> row.get("INDEX_TYPE_NAME").toString())
            .findFirst()
            .orElse("");
    }
}
