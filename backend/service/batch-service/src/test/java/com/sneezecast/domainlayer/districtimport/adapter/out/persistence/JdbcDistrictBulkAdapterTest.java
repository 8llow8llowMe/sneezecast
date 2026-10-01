package com.sneezecast.domainlayer.districtimport.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 실제 SQL 을 H2 MySQL 모드에서 돌린다. {@code ON DUPLICATE KEY UPDATE ... VALUES(col)} 를 H2 가 MySQL 처럼 해석하는지도 여기서 드러난다.
 * 스키마는 surveillance {@code DistrictEntity} 와 같은 DDL({@code districtimport/district-schema.sql})이다.
 */
class JdbcDistrictBulkAdapterTest {

    private static final LocalDateTime SYNCED_AT_2025 = LocalDateTime.of(2026, 3, 2, 10, 0);
    private static final LocalDateTime SYNCED_AT_2026 = LocalDateTime.of(2027, 3, 2, 10, 0);

    private JdbcTemplate jdbcTemplate;
    private JdbcDistrictBulkAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:district-bulk-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("districtimport/district-schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        adapter = new JdbcDistrictBulkAdapter(jdbcTemplate);
    }

    @Test
    @DisplayName("신규 코드는 id = 코드 숫자, valid_from_year = last_seen_year = 적재 연도, valid_to_year = null(현행), 시각 컬럼 = syncedAt")
    void insertsNewDistricts() {
        int upserted = adapter.upsertAll(List.of(district("11240660", "가락1동"), district("11240670", "가락2동")), 2025, SYNCED_AT_2025);

        assertThat(upserted).isEqualTo(2);
        Map<String, Object> row = row("11240660");
        assertThat(((Number) row.get("ID")).longValue()).isEqualTo(11240660L);
        assertThat(row.get("NAME")).isEqualTo("가락1동");
        assertThat(row.get("SIDO_CODE")).isEqualTo("11");
        assertThat(row.get("SIDO_NAME")).isEqualTo("서울특별시");
        assertThat(row.get("SIGUNGU_CODE")).isEqualTo("11240");
        assertThat(row.get("SIGUNGU_NAME")).isEqualTo("송파구");
        assertThat(((Number) row.get("VALID_FROM_YEAR")).intValue()).isEqualTo(2025);
        assertThat(row.get("VALID_TO_YEAR")).isNull();
        assertThat(((Number) row.get("LAST_SEEN_YEAR")).intValue()).isEqualTo(2025);
        // NOW()(DB 세션 시간대)가 아니라 JVM 의 syncedAt 이 그대로 들어간다.
        assertThat(timestamp(row, "SYNCED_AT")).isEqualTo(SYNCED_AT_2025);
        assertThat(timestamp(row, "CREATED_AT")).isEqualTo(SYNCED_AT_2025);
        assertThat(timestamp(row, "UPDATED_AT")).isEqualTo(SYNCED_AT_2025);
    }

    @Test
    @DisplayName("같은 코드를 다음 연도로 다시 적재하면 이름 · last_seen_year · synced_at · updated_at 은 갱신되고 valid_from_year · created_at 은 남는다")
    void reimportUpdatesNameButKeepsValidFromYear() {
        adapter.upsertAll(List.of(district("11240660", "가락1동")), 2025, SYNCED_AT_2025);

        adapter.upsertAll(List.of(district("11240660", "가락본동")), 2026, SYNCED_AT_2026);

        Map<String, Object> row = row("11240660");
        assertThat(row.get("NAME")).isEqualTo("가락본동");
        assertThat(((Number) row.get("VALID_FROM_YEAR")).intValue()).isEqualTo(2025);
        assertThat(((Number) row.get("LAST_SEEN_YEAR")).intValue()).isEqualTo(2026);
        assertThat(timestamp(row, "SYNCED_AT")).isEqualTo(SYNCED_AT_2026);
        assertThat(timestamp(row, "UPDATED_AT")).isEqualTo(SYNCED_AT_2026);
        assertThat(timestamp(row, "CREATED_AT")).isEqualTo(SYNCED_AT_2025);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("폐지됐던 코드가 새 스냅샷에 다시 나오면 valid_to_year 가 null(현행)로 돌아온다")
    void reappearingCodeIsRestored() {
        adapter.upsertAll(List.of(district("21120560", "녹산동")), 2024, SYNCED_AT_2025);
        adapter.retire(List.of("21120560"), 2024, SYNCED_AT_2025);
        assertThat(row("21120560").get("VALID_TO_YEAR")).isNotNull();

        adapter.upsertAll(List.of(district("21120560", "녹산동")), 2026, SYNCED_AT_2026);

        assertThat(row("21120560").get("VALID_TO_YEAR")).isNull();
        assertThat(adapter.findActiveCodes()).containsExactly("21120560");
    }

    @Test
    @DisplayName("폐지는 현행 행만 바꾼다 — 이미 폐지된 행의 폐지 연도와 last_seen_year 는 덮어쓰지 않는다")
    void retireTouchesOnlyActiveRows() {
        adapter.upsertAll(List.of(district("35012580", "금암1동"), district("35012590", "금암2동"), district("35012721", "금암동")), 2024, SYNCED_AT_2025);
        adapter.retire(List.of("35012580"), 2023, SYNCED_AT_2025);

        int retired = adapter.retire(List.of("35012580", "35012590"), 2024, SYNCED_AT_2026);

        assertThat(retired).isEqualTo(1);
        assertThat(((Number) row("35012580").get("VALID_TO_YEAR")).intValue()).isEqualTo(2023);
        assertThat(((Number) row("35012590").get("VALID_TO_YEAR")).intValue()).isEqualTo(2024);
        assertThat(((Number) row("35012590").get("LAST_SEEN_YEAR")).intValue()).isEqualTo(2024);
        assertThat(timestamp(row("35012590"), "UPDATED_AT")).isEqualTo(SYNCED_AT_2026);
        assertThat(row("35012721").get("VALID_TO_YEAR")).isNull();
        assertThat(adapter.findActiveCodes()).containsExactly("35012721");
    }

    @Test
    @DisplayName("청크 크기(500)를 넘는 upsert · 폐지도 전부 반영된다")
    void handlesMoreThanOneChunk() {
        List<ImportedDistrict> districts = new ArrayList<>();
        for (int index = 0; index < JdbcDistrictBulkAdapter.BATCH_SIZE + 10; index++) {
            districts.add(district("11%06d".formatted(index), "동" + index));
        }

        assertThat(adapter.upsertAll(districts, 2025, SYNCED_AT_2025)).isEqualTo(510);
        assertThat(adapter.retire(districts.stream().map(ImportedDistrict::code).toList(), 2025, SYNCED_AT_2026)).isEqualTo(510);
        assertThat(adapter.findActiveCodes()).isEmpty();
    }

    @Test
    @DisplayName("빈 테이블이면 마지막 적재 연도가 없다 (제한 없음)")
    void lastLoadedYearIsEmptyForEmptyTable() {
        assertThat(adapter.findLastLoadedYear()).isEmpty();
    }

    @Test
    @DisplayName("마지막 적재 연도는 MAX(last_seen_year) 다")
    void lastLoadedYearIsMaxLastSeenYear() {
        adapter.upsertAll(List.of(district("11240660", "가락1동")), 2024, SYNCED_AT_2025);
        adapter.upsertAll(List.of(district("21120562", "신호동")), 2025, SYNCED_AT_2025);

        assertThat(adapter.findLastLoadedYear()).hasValue(2025);
    }

    @Test
    @DisplayName("폐지만 있고 신규 코드가 없던 해도 마지막 적재 연도다 — 남은 코드의 last_seen_year 가 그 해로 오른다")
    void lastLoadedYearCountsRetireOnlyYear() {
        adapter.upsertAll(List.of(district("35012580", "금암1동"), district("35012721", "금암동")), 2025, SYNCED_AT_2025);
        // 2026 적재: 같은 코드를 다시 쓰고(valid_from_year 는 2025 로 남는다) 금암1동만 폐지한다.
        adapter.upsertAll(List.of(district("35012721", "금암동")), 2026, SYNCED_AT_2026);
        adapter.retire(List.of("35012580"), 2025, SYNCED_AT_2026);

        assertThat(adapter.findLastLoadedYear()).hasValue(2026);
    }

    @Test
    @DisplayName("폐지됐다 재등장한 코드는 valid_to_year 가 지워져도 last_seen_year 로 마지막 적재 연도가 남는다")
    void lastLoadedYearSurvivesReappearance() {
        adapter.upsertAll(List.of(district("21120560", "녹산동")), 2025, SYNCED_AT_2025);
        adapter.retire(List.of("21120560"), 2025, SYNCED_AT_2026);
        adapter.upsertAll(List.of(district("21120560", "녹산동")), 2027, SYNCED_AT_2026);

        assertThat(row("21120560").get("VALID_TO_YEAR")).isNull();
        assertThat(adapter.findLastLoadedYear()).hasValue(2027);
    }

    @Test
    @DisplayName("빈 목록은 SQL 을 보내지 않고 0 이다")
    void emptyInputIsNoop() {
        assertThat(adapter.upsertAll(List.of(), 2025, SYNCED_AT_2025)).isZero();
        assertThat(adapter.retire(List.of(), 2025, SYNCED_AT_2025)).isZero();
    }

    private static ImportedDistrict district(String code, String name) {
        String sidoCode = code.substring(0, 2);
        String sigunguCode = code.substring(0, 5);
        return new ImportedDistrict(code, name, sidoCode, "11".equals(sidoCode) ? "서울특별시" : "시도" + sidoCode, sigunguCode,
            "11240".equals(sigunguCode) ? "송파구" : "시군구" + sigunguCode);
    }

    private Map<String, Object> row(String code) {
        return jdbcTemplate.queryForMap("SELECT * FROM district WHERE code = ?", code);
    }

    private static LocalDateTime timestamp(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toLocalDateTime();
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM district", Integer.class);
    }
}
