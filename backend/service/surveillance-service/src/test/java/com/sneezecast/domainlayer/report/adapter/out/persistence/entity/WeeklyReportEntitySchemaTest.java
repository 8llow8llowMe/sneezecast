package com.sneezecast.domainlayer.report.adapter.out.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.SurveillanceH2TestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link WeeklyReportEntity} 가 만드는 스키마가 entity-design §2-1 과 같은지 본다. prod 는 DB 담당자가 DDL 을 적용하므로 엔티티와 문서가
 * 어긋나면 여기서 먼저 깨져야 한다. 특히 <b>{@code member_id} 컬럼이 생기지 않는지</b>를 컬럼 목록 전체로 못 박는다 (architecture-guide §6).
 */
class WeeklyReportEntitySchemaTest extends SurveillanceH2TestSupport {

    private static final String TABLE = "WEEKLY_REPORT";

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("컬럼이 설계와 같고 전부 NOT NULL 이다 — member_id 는 없다")
    void columnsMatchDesign() {
        Map<String, Map<String, Object>> columns = columns();

        assertThat(columns).containsOnlyKeys(
            "id", "reporter_key", "iso_week", "district_code", "symptom_mask", "revision_count", "created_at", "updated_at");
        assertThat(columns).doesNotContainKey("member_id");
        columns.forEach((column, row) -> assertThat(row.get("IS_NULLABLE").toString()).as(column).isEqualTo("NO"));
    }

    @Test
    @DisplayName("타입 · 길이가 설계와 같다 — reporter_key CHAR(64), iso_week CHAR(8), symptom_mask TINYINT, revision_count SMALLINT 기본 0")
    void columnTypesMatchDesign() {
        Map<String, Map<String, Object>> columns = columns();

        assertThat(dataType(columns, "id")).isEqualTo("BIGINT");
        assertThat(dataType(columns, "reporter_key")).isEqualTo("CHARACTER");
        assertThat(length(columns, "reporter_key")).isEqualTo(64);
        assertThat(dataType(columns, "iso_week")).isEqualTo("CHARACTER");
        assertThat(length(columns, "iso_week")).isEqualTo(8);
        assertThat(dataType(columns, "district_code")).isEqualTo("CHARACTER VARYING");
        assertThat(length(columns, "district_code")).isEqualTo(8);
        assertThat(dataType(columns, "symptom_mask")).isEqualTo("TINYINT");
        assertThat(dataType(columns, "revision_count")).isEqualTo("SMALLINT");
        assertThat(columns.get("revision_count").get("COLUMN_DEFAULT").toString()).isEqualTo("0");
        assertThat(dataType(columns, "created_at")).startsWith("TIMESTAMP");
        assertThat(dataType(columns, "updated_at")).startsWith("TIMESTAMP");
    }

    @Test
    @DisplayName("(reporter_key, iso_week) 유니크 uk_weekly_report_reporter_key_iso_week, (iso_week, district_code) 인덱스가 있다")
    void indexesMatchDesign() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT i.INDEX_NAME, i.INDEX_TYPE_NAME, c.COLUMN_NAME
            FROM INFORMATION_SCHEMA.INDEXES i
            JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
              ON c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME
            WHERE UPPER(i.TABLE_NAME) = 'WEEKLY_REPORT'
            ORDER BY c.ORDINAL_POSITION""");

        assertThat(indexColumns(indexes, WeeklyReportEntity.REPORTER_KEY_ISO_WEEK_UNIQUE_INDEX)).containsExactly("reporter_key", "iso_week");
        assertThat(indexType(indexes, WeeklyReportEntity.REPORTER_KEY_ISO_WEEK_UNIQUE_INDEX)).contains("UNIQUE");
        assertThat(indexColumns(indexes, "idx_weekly_report_iso_week_district_code")).containsExactly("iso_week", "district_code");
        assertThat(indexType(indexes, "idx_weekly_report_iso_week_district_code")).doesNotContain("UNIQUE");
    }

    @Test
    @DisplayName("모든 컬럼에 @Comment 가 붙어 있다")
    void everyColumnHasComment() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getColumns(null, null, TABLE, null)) {
            int count = 0;
            while (rs.next()) {
                assertThat(rs.getString("REMARKS")).as(rs.getString("COLUMN_NAME")).isNotBlank();
                count++;
            }
            assertThat(count).isEqualTo(8);
        }
    }

    private Map<String, Map<String, Object>> columns() {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE UPPER(TABLE_NAME) = '" + TABLE + "'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(Locale.ROOT), row -> row));
    }

    private static String dataType(Map<String, Map<String, Object>> columns, String column) {
        return columns.get(column).get("DATA_TYPE").toString();
    }

    private static int length(Map<String, Map<String, Object>> columns, String column) {
        return ((Number) columns.get(column).get("CHARACTER_MAXIMUM_LENGTH")).intValue();
    }

    private static List<String> indexColumns(List<Map<String, Object>> indexes, String indexName) {
        return indexes.stream()
            .filter(row -> row.get("INDEX_NAME").toString().toLowerCase(Locale.ROOT).startsWith(indexName))
            .map(row -> row.get("COLUMN_NAME").toString().toLowerCase(Locale.ROOT))
            .toList();
    }

    private static String indexType(List<Map<String, Object>> indexes, String indexName) {
        return indexes.stream()
            .filter(row -> row.get("INDEX_NAME").toString().toLowerCase(Locale.ROOT).startsWith(indexName))
            .map(row -> row.get("INDEX_TYPE_NAME").toString())
            .findFirst()
            .orElse("");
    }
}
