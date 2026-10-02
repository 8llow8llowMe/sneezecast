package com.sneezecast.domainlayer.official.adapter.out.persistence;

import static com.sneezecast.domainlayer.official.OfficialFixtures.RUN_STARTED_AT;
import static com.sneezecast.domainlayer.official.OfficialFixtures.SHA256;
import static com.sneezecast.domainlayer.official.OfficialFixtures.ariDraft;
import static org.assertj.core.api.Assertions.assertThat;

import com.sneezecast.domainlayer.official.OfficialFixtures;
import com.sneezecast.domainlayer.official.domain.enums.IngestChannel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.OfficialSnapshotDraft;
import com.sneezecast.domainlayer.official.domain.model.OfficialSourceSnapshot;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcOfficialSourceSnapshotBulkAdapterTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 2, 6, 0, 9);

    private JdbcTemplate jdbcTemplate;
    private JdbcOfficialSourceSnapshotBulkAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(OfficialFixtures.h2DataSource("official-snapshot"));
        adapter = new JdbcOfficialSourceSnapshotBulkAdapter(jdbcTemplate);
    }

    @Test
    @DisplayName("IMPORTED 기록은 초안 값 · 건수 · 시각이 그대로 들어가고 error_code 는 null 이다")
    void insertsImportedSnapshot() {
        adapter.insert(OfficialSourceSnapshot.imported(7001L, ariDraft("ari:2026-31~2026-38:age=ALL", 40), 32, CREATED_AT));

        Map<String, Object> row = row(7001L);
        assertThat(row.get("SOURCE")).isEqualTo("KDCA_SENTINEL");
        assertThat(row.get("PROGRAM")).isEqualTo("ARI");
        assertThat(row.get("REQUEST_KEY")).isEqualTo("ari:2026-31~2026-38:age=ALL");
        assertThat(row.get("CHANNEL")).isEqualTo("PORTAL_JSON");
        assertThat(row.get("CONTENT_SHA256")).isEqualTo(SHA256);
        assertThat(((Number) row.get("BYTE_LENGTH")).intValue()).isEqualTo(1024);
        assertThat(((Number) row.get("ROW_COUNT")).intValue()).isEqualTo(40);
        assertThat(((Number) row.get("IMPORTED_COUNT")).intValue()).isEqualTo(32);
        assertThat(row.get("STATUS")).isEqualTo("IMPORTED");
        assertThat(row.get("ERROR_CODE")).isNull();
        assertThat(timestamp(row, "RUN_STARTED_AT")).isEqualTo(RUN_STARTED_AT);
        assertThat(timestamp(row, "CREATED_AT")).isEqualTo(CREATED_AT);
        assertThat(timestamp(row, "UPDATED_AT")).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("FAILED 기록은 본문 해시 null · error_code · imported_count 0 으로 들어간다")
    void insertsFailedSnapshot() {
        OfficialSnapshotDraft draft = new OfficialSnapshotDraft(OfficialSource.KDCA_NOTIFIABLE, OfficialProgram.NOTIFIABLE,
            "notifiable:PeriodBasic:2026", IngestChannel.OPEN_API, null, 0, 0, RUN_STARTED_AT);

        adapter.insert(OfficialSourceSnapshot.failed(7002L, draft, "NOTIFIABLE_IMPORT_004", CREATED_AT));

        Map<String, Object> row = row(7002L);
        assertThat(row.get("SOURCE")).isEqualTo("KDCA_NOTIFIABLE");
        assertThat(row.get("CHANNEL")).isEqualTo("OPEN_API");
        assertThat(row.get("CONTENT_SHA256")).isNull();
        assertThat(((Number) row.get("BYTE_LENGTH")).intValue()).isZero();
        assertThat(((Number) row.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
        assertThat(row.get("ERROR_CODE")).isEqualTo("NOTIFIABLE_IMPORT_004");
    }

    @Test
    @DisplayName("같은 조회 조건으로 여러 번 실행하면 행이 쌓인다 (덮어쓰지 않는다)")
    void appendsOnly() {
        adapter.insert(OfficialSourceSnapshot.imported(7001L, ariDraft("ari", 1), 1, CREATED_AT));
        adapter.insert(OfficialSourceSnapshot.failed(7002L, ariDraft("ari", 0), "SENTINEL_SCHEMA_CHANGED", CREATED_AT.plusWeeks(1)));

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE request_key = 'ari'", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM official_source_snapshot WHERE id = ?", id);
    }

    private static LocalDateTime timestamp(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toLocalDateTime();
    }
}
