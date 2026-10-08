package com.sneezecast.domainlayer.aggregate.adapter.out.persistence.entity;

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
 * {@link DistrictWeeklyAggregateEntity} 가 만드는 스키마가 entity-design §2-2 와 같은지 본다. prod 는 DB 담당자가 DDL 을 적용하므로 엔티티와 문서가
 * 어긋나면 여기서 먼저 깨져야 한다. 익명 집계라 회원 · 보고자 컬럼이 생기지 않는지도 컬럼 목록 전체로 못 박는다.
 */
class DistrictWeeklyAggregateEntitySchemaTest extends SurveillanceH2TestSupport {

    private static final String TABLE = "DISTRICT_WEEKLY_AGGREGATE";
    private static final Set<String> NULLABLE = Set.of("insufficient_reason", "baseline_participant_count", "baseline_symptomatic_count", "finalized_at");

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("컬럼이 설계와 같다 — 판정 이유 · 기준선 · 마감 시각만 null 을 받는다")
    void columnsMatchDesign() {
        Map<String, Map<String, Object>> columns = columns();

        assertThat(columns).containsOnlyKeys(
            "id", "district_code", "iso_week", "participant_count", "symptomatic_count", "respiratory_count", "enteric_count", "revised_report_count",
            "level", "insufficient_reason", "baseline_participant_count", "baseline_symptomatic_count", "rule_version", "calculated_at", "finalized_at",
            "created_at", "updated_at");
        columns.forEach((column, row) -> assertThat(row.get("IS_NULLABLE").toString()).as(column).isEqualTo(NULLABLE.contains(column) ? "YES" : "NO"));
    }

    @Test
    @DisplayName("타입 · 길이가 설계와 같다 — iso_week CHAR(8), 수치 INT, 단계 · 이유는 enum 이 아니라 VARCHAR(20), 시각 TIMESTAMP")
    void columnTypesMatchDesign() {
        Map<String, Map<String, Object>> columns = columns();

        assertThat(dataType(columns, "id")).isEqualTo("BIGINT");
        assertThat(dataType(columns, "district_code")).isEqualTo("CHARACTER VARYING");
        assertThat(length(columns, "district_code")).isEqualTo(8);
        assertThat(dataType(columns, "iso_week")).isEqualTo("CHARACTER");
        assertThat(length(columns, "iso_week")).isEqualTo(8);
        for (String count : List.of("participant_count", "symptomatic_count", "respiratory_count", "enteric_count", "revised_report_count",
            "baseline_participant_count", "baseline_symptomatic_count")) {
            assertThat(dataType(columns, count)).as(count).isEqualTo("INTEGER");
        }
        for (String varchar20 : List.of("level", "insufficient_reason", "rule_version")) {
            assertThat(dataType(columns, varchar20)).as(varchar20).isEqualTo("CHARACTER VARYING");
            assertThat(length(columns, varchar20)).as(varchar20).isEqualTo(20);
        }
        for (String time : List.of("calculated_at", "finalized_at", "created_at", "updated_at")) {
            assertThat(dataType(columns, time)).as(time).startsWith("TIMESTAMP");
        }
    }

    @Test
    @DisplayName("(district_code, iso_week) 유니크 uk_district_weekly_aggregate_district_code_iso_week, iso_week 인덱스가 있다")
    void indexesMatchDesign() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
            SELECT i.INDEX_NAME, i.INDEX_TYPE_NAME, c.COLUMN_NAME
            FROM INFORMATION_SCHEMA.INDEXES i
            JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c
              ON c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME
            WHERE UPPER(i.TABLE_NAME) = 'DISTRICT_WEEKLY_AGGREGATE'
            ORDER BY c.ORDINAL_POSITION""");

        String unique = DistrictWeeklyAggregateEntity.DISTRICT_CODE_ISO_WEEK_UNIQUE_INDEX;
        assertThat(indexColumns(indexes, unique)).containsExactly("district_code", "iso_week");
        assertThat(indexType(indexes, unique)).contains("UNIQUE");
        assertThat(indexColumns(indexes, "idx_district_weekly_aggregate_iso_week")).containsExactly("iso_week");
        assertThat(indexType(indexes, "idx_district_weekly_aggregate_iso_week")).doesNotContain("UNIQUE");
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
            assertThat(count).isEqualTo(17);
        }
    }

    private Map<String, Map<String, Object>> columns() {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '" + TABLE + "'")
            .stream()
            .collect(Collectors.toMap(row -> row.get("COLUMN_NAME").toString().toLowerCase(Locale.ROOT), row -> row));
    }

    private static String dataType(Map<String, Map<String, Object>> columns, String column) {
        return columns.get(column).get("DATA_TYPE").toString();
    }

    private static int length(Map<String, Map<String, Object>> columns, String column) {
        return ((Number) columns.get(column).get("CHARACTER_MAXIMUM_LENGTH")).intValue();
    }

    // 접두어로 찾되, 유니크 이름이 iso_week 인덱스 이름의 접두어가 되지 않으므로 서로 섞이지 않는다.
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
