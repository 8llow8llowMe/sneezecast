package com.sneezecast.domainlayer.official.adapter.out.persistence;

import static com.sneezecast.domainlayer.official.OfficialFixtures.ariWeek;
import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.official.OfficialFixtures;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 SQL 을 H2 MySQL 모드에서 돌린다. PK(Snowflake)가 아니라 자연키 UK 에 걸린 {@code ON DUPLICATE KEY UPDATE} 를 H2 가 MySQL 처럼 해석하는지도
 * 여기서 드러난다. 스키마는 surveillance 엔티티와 같은 DDL({@code official/official-schema.sql})이다.
 */
class JdbcOfficialSurveillanceBulkAdapterTest {

    private static final LocalDateTime FIRST_SYNC = LocalDateTime.of(2026, 9, 25, 6, 0, 5);
    private static final LocalDateTime SECOND_SYNC = LocalDateTime.of(2026, 10, 2, 6, 0, 7);

    private JdbcTemplate jdbcTemplate;
    private JdbcOfficialSurveillanceBulkAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(OfficialFixtures.h2DataSource("official-bulk"));
        adapter = new JdbcOfficialSurveillanceBulkAdapter(jdbcTemplate);
    }

    @Test
    @DisplayName("신규 행은 넘긴 id 로 들어가고, 모든 컬럼이 원천 값 · syncedAt 으로 채워진다")
    void insertsNewRow() {
        int upserted = adapter.upsertAll(List.of(new IdentifiedOfficialRecord(101L, ariWeek("ND0715", 38, "12.5"))), 9001L, FIRST_SYNC);

        assertThat(upserted).isEqualTo(1);
        Map<String, Object> row = onlyRow();
        assertThat(((Number) row.get("ID")).longValue()).isEqualTo(101L);
        assertThat(row.get("SOURCE")).isEqualTo("KDCA_SENTINEL");
        assertThat(row.get("PROGRAM")).isEqualTo("ARI");
        assertThat(row.get("DISEASE_KEY")).isEqualTo("ND0715");
        assertThat(row.get("DISEASE_NAME")).isEqualTo("병원체 ND0715");
        assertThat(row.get("DISEASE_GROUP")).isEqualTo("바이러스");
        assertThat(row.get("METRIC")).isEqualTo("CASE_COUNT");
        assertThat(row.get("AGE_GROUP")).isEqualTo("ALL");
        assertThat(row.get("REGION_LEVEL")).isEqualTo("NATION");
        assertThat(row.get("REGION_CODE")).isEqualTo("00");
        assertThat(row.get("REGION_NAME")).isEqualTo("전국");
        assertThat(row.get("PERIOD_TYPE")).isEqualTo("WEEK");
        assertThat(((Number) row.get("PERIOD_YEAR")).intValue()).isEqualTo(2026);
        assertThat(((Number) row.get("PERIOD_WEEK")).intValue()).isEqualTo(38);
        assertThat(date(row, "PERIOD_START")).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(date(row, "PERIOD_END")).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat((BigDecimal) row.get("METRIC_VALUE")).isEqualByComparingTo("12.5");
        assertThat(((Number) row.get("SOURCE_SNAPSHOT_ID")).longValue()).isEqualTo(9001L);
        assertThat(timestamp(row, "SYNCED_AT")).isEqualTo(FIRST_SYNC);
        assertThat(timestamp(row, "CREATED_AT")).isEqualTo(FIRST_SYNC);
        assertThat(timestamp(row, "UPDATED_AT")).isEqualTo(FIRST_SYNC);
    }

    @Test
    @DisplayName("같은 자연키를 다른 id 로 다시 쓰면 행은 하나 — id · created_at 은 남고 값 · 감염병 · 지역 이름 · 쓴 실행 · synced_at · updated_at 이 갱신된다")
    void reimportKeepsIdAndCreatedAt() {
        adapter.upsertAll(List.of(new IdentifiedOfficialRecord(101L, ariWeek("ND0715", 38, "12"))), 9001L, FIRST_SYNC);

        // 잠정 통계라 지난 주 값이 늘어난다. 새 Snowflake id(202)는 버려진다.
        OfficialRecord revised = withNames(ariWeek("ND0715", 38, "15.25"), "노로바이러스", "전국 (수정)");
        adapter.upsertAll(List.of(new IdentifiedOfficialRecord(202L, revised)), 9002L, SECOND_SYNC);

        assertThat(count()).isEqualTo(1);
        Map<String, Object> row = onlyRow();
        assertThat(((Number) row.get("ID")).longValue()).isEqualTo(101L);
        assertThat(timestamp(row, "CREATED_AT")).isEqualTo(FIRST_SYNC);
        assertThat(row.get("DISEASE_NAME")).isEqualTo("노로바이러스");
        assertThat(row.get("REGION_NAME")).isEqualTo("전국 (수정)");
        assertThat((BigDecimal) row.get("METRIC_VALUE")).isEqualByComparingTo("15.25");
        assertThat(((Number) row.get("SOURCE_SNAPSHOT_ID")).longValue()).isEqualTo(9002L);
        assertThat(timestamp(row, "SYNCED_AT")).isEqualTo(SECOND_SYNC);
        assertThat(timestamp(row, "UPDATED_AT")).isEqualTo(SECOND_SYNC);
    }

    @Test
    @DisplayName("원천이 빈 칸이면 metric_value · disease_group 은 null 로 저장된다 (0 이 아니다)")
    void storesNullValue() {
        OfficialRecord blank = ariWeek("ND0715", 38, null);
        OfficialRecord withoutGroup = new OfficialRecord(blank.source(), blank.program(), blank.diseaseKey(), blank.diseaseName(), null,
            blank.metric(), blank.ageGroup(), blank.regionLevel(), blank.regionCode(), blank.regionName(), blank.periodType(), blank.periodYear(),
            blank.periodWeek(), blank.periodStart(), blank.periodEnd(), null);

        adapter.upsertAll(List.of(new IdentifiedOfficialRecord(101L, withoutGroup)), 9001L, FIRST_SYNC);

        Map<String, Object> row = onlyRow();
        assertThat(row.get("METRIC_VALUE")).isNull();
        assertThat(row.get("DISEASE_GROUP")).isNull();
    }

    @Test
    @DisplayName("값이 있던 행을 빈 칸으로 다시 쓰면 null 로 갱신된다 — 원천이 값을 거둬들이면 그대로 따른다")
    void reimportCanClearValue() {
        adapter.upsertAll(List.of(new IdentifiedOfficialRecord(101L, ariWeek("ND0715", 38, "12"))), 9001L, FIRST_SYNC);
        adapter.upsertAll(List.of(new IdentifiedOfficialRecord(202L, ariWeek("ND0715", 38, null))), 9002L, SECOND_SYNC);

        assertThat(onlyRow().get("METRIC_VALUE")).isNull();
    }

    @Test
    @DisplayName("청크 크기(500)를 두 번 넘는 1,001건도 한 번에 전부 들어간다")
    void handlesMoreThanTwoChunks() {
        List<IdentifiedOfficialRecord> rows = new ArrayList<>();
        for (int index = 0; index < JdbcOfficialSurveillanceBulkAdapter.BATCH_SIZE * 2 + 1; index++) {
            rows.add(new IdentifiedOfficialRecord(1000L + index, ariWeek("ND%04d".formatted(index), 38, String.valueOf(index))));
        }

        assertThat(adapter.upsertAll(rows, 9001L, FIRST_SYNC)).isEqualTo(1001);
        assertThat(count()).isEqualTo(1001);
    }

    @Test
    @DisplayName("자연키 10 컬럼 중 하나라도 다르면 별도 행이다")
    void anyNaturalKeyDifferenceMakesNewRow() {
        OfficialRecord base = ariWeek("ND0715", 38, "1");
        List<OfficialRecord> variants = List.of(
            base,
            copy(base, OfficialSource.KDCA_NOTIFIABLE, base.program(), base.diseaseKey(), base.metric(), base.ageGroup()),
            copy(base, base.source(), OfficialProgram.ENTERIC, base.diseaseKey(), base.metric(), base.ageGroup()),
            copy(base, base.source(), base.program(), "TOTAL", base.metric(), base.ageGroup()),
            copy(base, base.source(), base.program(), base.diseaseKey(), OfficialMetric.INCIDENCE_PER_100K, base.ageGroup()),
            copy(base, base.source(), base.program(), base.diseaseKey(), base.metric(), OfficialAgeGroup.AGE_65_PLUS),
            region(base, "01"),
            region(base, "02"),
            ariWeek("ND0715", 37, "1"),
            new OfficialRecord(base.source(), base.program(), base.diseaseKey(), base.diseaseName(), base.diseaseGroup(), base.metric(),
                base.ageGroup(), base.regionLevel(), base.regionCode(), base.regionName(), base.periodType(), 2025, 38,
                LocalDate.of(2025, 9, 14), LocalDate.of(2025, 9, 20), BigDecimal.ONE),
            new OfficialRecord(base.source(), base.program(), base.diseaseKey(), base.diseaseName(), base.diseaseGroup(), base.metric(),
                base.ageGroup(), base.regionLevel(), base.regionCode(), base.regionName(), OfficialPeriodType.YEAR, 2026, 0,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), BigDecimal.ONE)
        );
        List<IdentifiedOfficialRecord> rows = new ArrayList<>();
        for (int index = 0; index < variants.size(); index++) {
            rows.add(new IdentifiedOfficialRecord(100L + index, variants.get(index)));
        }

        adapter.upsertAll(rows, 9001L, FIRST_SYNC);

        assertThat(count()).isEqualTo(variants.size());
    }

    @Test
    @DisplayName("빈 목록은 SQL 을 보내지 않고 0 이다")
    void emptyInputIsNoop() {
        assertThat(adapter.upsertAll(List.of(), 9001L, FIRST_SYNC)).isZero();
    }

    private static OfficialRecord withNames(OfficialRecord record, String diseaseName, String regionName) {
        return new OfficialRecord(record.source(), record.program(), record.diseaseKey(), diseaseName, record.diseaseGroup(), record.metric(),
            record.ageGroup(), record.regionLevel(), record.regionCode(), regionName, record.periodType(), record.periodYear(),
            record.periodWeek(), record.periodStart(), record.periodEnd(), record.metricValue());
    }

    private static OfficialRecord copy(OfficialRecord record, OfficialSource source, OfficialProgram program, String diseaseKey,
        OfficialMetric metric, OfficialAgeGroup ageGroup) {
        return new OfficialRecord(source, program, diseaseKey, record.diseaseName(), record.diseaseGroup(), metric, ageGroup,
            record.regionLevel(), record.regionCode(), record.regionName(), record.periodType(), record.periodYear(), record.periodWeek(),
            record.periodStart(), record.periodEnd(), record.metricValue());
    }

    private static OfficialRecord region(OfficialRecord record, String sidoCode) {
        return new OfficialRecord(record.source(), record.program(), record.diseaseKey(), record.diseaseName(), record.diseaseGroup(),
            record.metric(), record.ageGroup(), OfficialRegionLevel.SIDO, sidoCode, "시도" + sidoCode, record.periodType(), record.periodYear(),
            record.periodWeek(), record.periodStart(), record.periodEnd(), record.metricValue());
    }

    private Map<String, Object> onlyRow() {
        return jdbcTemplate.queryForMap("SELECT * FROM official_surveillance");
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class);
    }

    private static LocalDateTime timestamp(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toLocalDateTime();
    }

    private static LocalDate date(Map<String, Object> row, String column) {
        return ((Date) row.get(column)).toLocalDate();
    }
}
