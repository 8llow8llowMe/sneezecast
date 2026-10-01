package com.sneezecast.domainlayer.district.adapter.out.persistence.entity;

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
 * {@link DistrictEntity} 가 만드는 스키마가 entity-design §3-1 과 batch 의 upsert SQL 이 전제하는 구조인지 본다.
 *
 * <p>batch-service 는 이 테이블을 JDBC 로 쓰고({@code ON DUPLICATE KEY UPDATE} 는 {@code uk_district_code} · PK 에 기대고), 테스트 DDL
 * ({@code batch-service/src/test/resources/districtimport/district-schema.sql})도 이 엔티티를 베낀다. 엔티티가 바뀌면 여기서 먼저 깨진다.
 * 컨텍스트 구성은 {@code SurveillanceServiceApplicationTests} 와 같다 (dev 프로파일, H2, 외부 연결 없음).
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-district-schema;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-surveillance-district-schema-test-access-key-0123456789-0123456789",
    "REPORTER_KEY_PEPPER=sneezecast-district-schema-test-pepper-0123456789"
})
class DistrictEntitySchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("district 테이블의 컬럼과 NULL 여부가 설계와 같다 — valid_to_year 만 nullable(null = 현행)")
    void columnsMatchDesign() {
        Map<String, String> nullable = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = 'DISTRICT'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(), row -> row.get("IS_NULLABLE").toString()));

        assertThat(nullable).containsOnlyKeys(
            "id", "code", "name", "sido_code", "sido_name", "sigungu_code", "sigungu_name",
            "valid_from_year", "valid_to_year", "last_seen_year", "synced_at", "created_at", "updated_at");
        assertThat(nullable).containsEntry("valid_to_year", "YES");
        nullable.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("valid_to_year"))
            .forEach(entry -> assertThat(entry.getValue()).as(entry.getKey()).isEqualTo("NO"));
    }

    @Test
    @DisplayName("문자열 길이 · 연도 타입이 설계와 같다")
    void columnTypesMatchDesign() {
        Map<String, Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = 'DISTRICT'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(), row -> row));

        assertThat(length(columns, "code")).isEqualTo(8);
        assertThat(length(columns, "name")).isEqualTo(50);
        assertThat(length(columns, "sido_code")).isEqualTo(2);
        assertThat(length(columns, "sido_name")).isEqualTo(30);
        assertThat(length(columns, "sigungu_code")).isEqualTo(5);
        assertThat(length(columns, "sigungu_name")).isEqualTo(30);
        assertThat(columns.get("id").get("DATA_TYPE").toString()).isEqualTo("BIGINT");
        assertThat(columns.get("valid_from_year").get("DATA_TYPE").toString()).isEqualTo("SMALLINT");
        assertThat(columns.get("valid_to_year").get("DATA_TYPE").toString()).isEqualTo("SMALLINT");
        assertThat(columns.get("last_seen_year").get("DATA_TYPE").toString()).isEqualTo("SMALLINT");
        assertThat(columns.get("synced_at").get("DATA_TYPE").toString()).startsWith("TIMESTAMP");
    }

    @Test
    @DisplayName("code 에 유니크 uk_district_code, sigungu_code 에 일반 인덱스 idx_district_sigungu_code 가 있다")
    void indexesMatchDesign() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT i.INDEX_NAME, i.INDEX_TYPE_NAME, c.COLUMN_NAME
            FROM INFORMATION_SCHEMA.INDEXES i
            JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
              ON c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME
            WHERE UPPER(i.TABLE_NAME) = 'DISTRICT'""");

        assertThat(indexColumns(indexes, "uk_district_code")).containsExactly("code");
        assertThat(indexType(indexes, "uk_district_code")).contains("UNIQUE");
        assertThat(indexColumns(indexes, "idx_district_sigungu_code")).containsExactly("sigungu_code");
        assertThat(indexType(indexes, "idx_district_sigungu_code")).doesNotContain("UNIQUE");
    }

    private static int length(Map<String, Map<String, Object>> columns, String column) {
        return ((Number) columns.get(column).get("CHARACTER_MAXIMUM_LENGTH")).intValue();
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
