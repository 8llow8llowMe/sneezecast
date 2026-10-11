package com.sneezecast.domainlayer.advisory.adapter.out.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.SurveillanceH2TestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AdvisoryEntity} · {@link AdvisoryHistoryEntity} 가 만드는 스키마가 entity-design §2-3 · §2-4 와 같은지 본다. prod 는 DB 담당자가 DDL 을
 * 적용하므로 엔티티와 문서가 어긋나면 여기서 먼저 깨져야 한다. 회원 컬럼이 {@code advisory_history.operator_id} 하나뿐인지도 컬럼 목록 전체로 못 박는다.
 */
class AdvisoryEntitySchemaTest extends SurveillanceH2TestSupport {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("advisory 컬럼이 설계와 같다 — 정정 대상 · 발행 · 철회 시각만 null 을 받고, 회원 · 운영자 컬럼이 없다")
    void advisoryColumnsMatchDesign() {
        Map<String, Map<String, Object>> columns = columns("ADVISORY");
        Set<String> nullable = Set.of("corrects_advisory_id", "published_at", "retracted_at");

        assertThat(columns).containsOnlyKeys(
            "id", "district_code", "iso_week", "aggregate_id", "status", "title", "body", "cited_participant_count", "cited_symptomatic_count",
            "cited_respiratory_count", "cited_enteric_count", "cited_level", "cited_rule_version", "corrects_advisory_id", "published_at", "retracted_at",
            "version", "created_at", "updated_at");
        columns.forEach((column, row) -> assertThat(row.get("IS_NULLABLE").toString()).as(column).isEqualTo(nullable.contains(column) ? "YES" : "NO"));
        assertThat(dataType(columns, "iso_week")).isEqualTo("CHARACTER");
        assertThat(length(columns, "iso_week")).isEqualTo(8);
        assertThat(length(columns, "district_code")).isEqualTo(8);
        assertThat(length(columns, "title")).isEqualTo(100);
        // TEXT — H2 MySQL 모드는 상한이 큰 VARCHAR 로 만든다. 제목처럼 짧은 VARCHAR 로 잘리지 않는지만 본다.
        assertThat(length(columns, "body")).isGreaterThanOrEqualTo(65_535);
        for (String varchar20 : List.of("status", "cited_level", "cited_rule_version")) {
            assertThat(dataType(columns, varchar20)).as(varchar20).isEqualTo("CHARACTER VARYING");
            assertThat(length(columns, varchar20)).as(varchar20).isEqualTo(20);
        }
        for (String count : List.of("cited_participant_count", "cited_symptomatic_count", "cited_respiratory_count", "cited_enteric_count")) {
            assertThat(dataType(columns, count)).as(count).isEqualTo("INTEGER");
        }
        for (String id : List.of("id", "aggregate_id", "corrects_advisory_id", "version")) {
            assertThat(dataType(columns, id)).as(id).isEqualTo("BIGINT");
        }
        for (String time : List.of("published_at", "retracted_at", "created_at", "updated_at")) {
            assertThat(dataType(columns, time)).as(time).startsWith("TIMESTAMP");
        }
    }

    @Test
    @DisplayName("advisory_history 컬럼이 설계와 같다 — 이전 상태 · AI 정보 · 메모만 null 을 받는다")
    void historyColumnsMatchDesign() {
        Map<String, Map<String, Object>> columns = columns("ADVISORY_HISTORY");
        Set<String> nullable = Set.of("from_status", "ai_model", "prompt_version", "memo");

        assertThat(columns).containsOnlyKeys(
            "id", "advisory_id", "from_status", "to_status", "title", "body", "operator_id", "ai_model", "prompt_version", "memo", "created_at",
            "updated_at");
        columns.forEach((column, row) -> assertThat(row.get("IS_NULLABLE").toString()).as(column).isEqualTo(nullable.contains(column) ? "YES" : "NO"));
        assertThat(length(columns, "from_status")).isEqualTo(20);
        assertThat(length(columns, "to_status")).isEqualTo(20);
        assertThat(length(columns, "title")).isEqualTo(100);
        // TEXT — H2 MySQL 모드는 상한이 큰 VARCHAR 로 만든다. 제목처럼 짧은 VARCHAR 로 잘리지 않는지만 본다.
        assertThat(length(columns, "body")).isGreaterThanOrEqualTo(65_535);
        assertThat(dataType(columns, "operator_id")).isEqualTo("BIGINT");
        assertThat(length(columns, "ai_model")).isEqualTo(60);
        assertThat(length(columns, "prompt_version")).isEqualTo(20);
        assertThat(length(columns, "memo")).isEqualTo(500);
    }

    @Test
    @DisplayName("인덱스가 설계와 같다 — 공개 목록 (district_code, status, published_at), 근거 집계, 정정 대상, 이력의 안내")
    void indexesMatchDesign() {
        assertThat(indexColumns("ADVISORY", "idx_advisory_district_code_status_published_at")).containsExactly("district_code", "status", "published_at");
        assertThat(indexColumns("ADVISORY", "idx_advisory_aggregate_id")).containsExactly("aggregate_id");
        assertThat(indexColumns("ADVISORY", "idx_advisory_corrects_advisory_id")).containsExactly("corrects_advisory_id");
        assertThat(indexColumns("ADVISORY_HISTORY", "idx_advisory_history_advisory_id")).containsExactly("advisory_id");
    }

    @Test
    @DisplayName("두 테이블의 모든 컬럼에 @Comment 가 붙어 있다")
    void everyColumnHasComment() throws SQLException {
        assertThat(commentedColumns("ADVISORY")).isEqualTo(19);
        assertThat(commentedColumns("ADVISORY_HISTORY")).isEqualTo(12);
    }

    private int commentedColumns(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
            int count = 0;
            while (rs.next()) {
                assertThat(rs.getString("REMARKS")).as(table + "." + rs.getString("COLUMN_NAME")).isNotBlank();
                count++;
            }
            return count;
        }
    }

    private Map<String, Map<String, Object>> columns(String table) {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ?", table)
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(Locale.ROOT), row -> row));
    }

    private static String dataType(Map<String, Map<String, Object>> columns, String column) {
        return columns.get(column).get("DATA_TYPE").toString();
    }

    private static int length(Map<String, Map<String, Object>> columns, String column) {
        return ((Number) columns.get(column).get("CHARACTER_MAXIMUM_LENGTH")).intValue();
    }

    // 이름이 같은 인덱스만 고른다 — idx_advisory_aggregate_id 와 idx_advisory_history_advisory_id 처럼 테이블이 다르면 섞이지 않는다.
    private List<String> indexColumns(String table, String indexName) {
        return jdbcTemplate.queryForList("""
                SELECT c.COLUMN_NAME
                FROM INFORMATION_SCHEMA.INDEXES i
                JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
                  ON c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME
                WHERE UPPER(i.TABLE_NAME) = ? AND LOWER(i.INDEX_NAME) = ? AND i.INDEX_TYPE_NAME NOT LIKE '%UNIQUE%'
                ORDER BY c.ORDINAL_POSITION""", String.class, table, indexName)
            .stream()
            .map(column -> column.toLowerCase(Locale.ROOT))
            .toList();
    }
}
