package com.sneezecast.domainlayer.official.application.service.processor;

import static com.sneezecast.domainlayer.official.OfficialFixtures.ariDraft;
import static com.sneezecast.domainlayer.official.OfficialFixtures.ariWeek;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sneezecast.domainlayer.official.OfficialFixtures;
import com.sneezecast.domainlayer.official.adapter.out.persistence.JdbcOfficialSourceSnapshotBulkAdapter;
import com.sneezecast.domainlayer.official.adapter.out.persistence.JdbcOfficialSurveillanceBulkAdapter;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestErrorCode;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestException;
import com.sneezecast.domainlayer.official.application.model.OfficialIngestResult;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSourceSnapshotBulkPort;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceBulkPort;
import com.sneezecast.domainlayer.official.domain.enums.IngestStatus;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.enums.OfficialMetric;
import com.sneezecast.domainlayer.official.domain.enums.OfficialPeriodType;
import com.sneezecast.domainlayer.official.domain.enums.OfficialProgram;
import com.sneezecast.domainlayer.official.domain.enums.OfficialRegionLevel;
import com.sneezecast.domainlayer.official.domain.enums.OfficialSource;
import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.OfficialSourceSnapshot;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 JDBC 어댑터(H2 MySQL 모드) + primary 매니저(DataSourceTransactionManager)로 쓰기 순서 · 롤백 · FAILED 기록을 본다.
 */
class OfficialIngestProcessorTest {

    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 10, 2, 6, 0, 30);
    private static final LocalDateTime NEXT_SYNCED_AT = LocalDateTime.of(2026, 10, 9, 6, 0, 30);

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private JdbcOfficialSurveillanceBulkAdapter surveillanceAdapter;
    private JdbcOfficialSourceSnapshotBulkAdapter snapshotAdapter;
    private OfficialIngestProcessor processor;

    @BeforeEach
    void setUp() {
        dataSource = OfficialFixtures.h2DataSource("official-ingest");
        jdbcTemplate = new JdbcTemplate(dataSource);
        surveillanceAdapter = new JdbcOfficialSurveillanceBulkAdapter(jdbcTemplate);
        snapshotAdapter = new JdbcOfficialSourceSnapshotBulkAdapter(jdbcTemplate);
        processor = processor(surveillanceAdapter, snapshotAdapter);
    }

    @Test
    @DisplayName("성공: IMPORTED 수집 기록 1행(row_count = 원천 행 수, imported_count = 쓴 행 수) + 모든 행이 그 기록 id 를 참조한다")
    void importsSnapshotAndRows() {
        List<OfficialRecord> records = List.of(ariWeek("ND0715", 37, "10"), ariWeek("ND0715", 38, "12"), ariWeek("TOTAL", 38, "40"));

        OfficialIngestResult result = processor.ingest(ariDraft("ari:2026-37~2026-38:age=ALL", 5), records, SYNCED_AT);

        assertThat(result.importedCount()).isEqualTo(3);
        Map<String, Object> snapshot = jdbcTemplate.queryForMap("SELECT * FROM official_source_snapshot");
        assertThat(((Number) snapshot.get("ID")).longValue()).isEqualTo(result.snapshotId());
        assertThat(snapshot.get("STATUS")).isEqualTo("IMPORTED");
        assertThat(((Number) snapshot.get("ROW_COUNT")).intValue()).isEqualTo(5);
        assertThat(((Number) snapshot.get("IMPORTED_COUNT")).intValue()).isEqualTo(3);
        assertThat(snapshot.get("ERROR_CODE")).isNull();
        assertThat(((Timestamp) snapshot.get("CREATED_AT")).toLocalDateTime()).isEqualTo(SYNCED_AT);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source_snapshot_id FROM official_surveillance", Long.class))
            .containsExactly(result.snapshotId());
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT synced_at FROM official_surveillance", Timestamp.class))
            .containsExactly(Timestamp.valueOf(SYNCED_AT));
        assertThat(surveillanceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("재적재: 같은 자연키 행은 id 가 그대로이고 값과 source_snapshot_id 가 새 실행으로 바뀐다. 수집 기록은 쌓인다")
    void reimportKeepsRowIds() {
        OfficialIngestResult first = processor.ingest(ariDraft("ari", 1), List.of(ariWeek("ND0715", 38, "12")), SYNCED_AT);
        Long firstRowId = jdbcTemplate.queryForObject("SELECT id FROM official_surveillance", Long.class);

        OfficialIngestResult second = processor.ingest(ariDraft("ari", 1), List.of(ariWeek("ND0715", 38, "15")), NEXT_SYNCED_AT);

        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM official_surveillance");
        assertThat(((Number) row.get("ID")).longValue()).isEqualTo(firstRowId);
        assertThat((BigDecimal) row.get("METRIC_VALUE")).isEqualByComparingTo("15");
        assertThat(((Number) row.get("SOURCE_SNAPSHOT_ID")).longValue()).isEqualTo(second.snapshotId());
        assertThat(snapshotCount(IngestStatus.IMPORTED)).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 결과도 IMPORTED 수집 기록(imported_count 0)을 남긴다")
    void emptyRecordsStillLeaveImportedSnapshot() {
        OfficialIngestResult result = processor.ingest(ariDraft("ari", 0), List.of(), SYNCED_AT);

        assertThat(result.importedCount()).isZero();
        assertThat(snapshotCount(IngestStatus.IMPORTED)).isEqualTo(1);
        assertThat(surveillanceCount()).isZero();
    }

    @Test
    @DisplayName("upsert 도중 실패: 수집 기록 · 행 모두 롤백되고, FAILED 기록(OFFICIAL_INGEST_003, imported_count 0)만 남고, 원래 예외가 그대로 나간다")
    void writeFailureRollsBackAndRecordsFailed() {
        IllegalStateException boom = new IllegalStateException("upsert failed");
        OfficialIngestProcessor failing = processor(new FailingAfterUpsertPort(surveillanceAdapter, boom), snapshotAdapter);

        assertThatThrownBy(() -> failing.ingest(ariDraft("ari", 2), List.of(ariWeek("ND0715", 37, "1"), ariWeek("ND0715", 38, "2")), SYNCED_AT))
            .isSameAs(boom);

        assertThat(surveillanceCount()).isZero();
        assertThat(snapshotCount(IngestStatus.IMPORTED)).isZero();
        Map<String, Object> failed = jdbcTemplate.queryForMap("SELECT * FROM official_source_snapshot");
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("ERROR_CODE")).isEqualTo(OfficialIngestErrorCode.WRITE_FAILED.getCode());
        assertThat(((Number) failed.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(((Number) failed.get("ROW_COUNT")).intValue()).isEqualTo(2);
        assertThat(((Timestamp) failed.get("CREATED_AT")).toLocalDateTime()).isEqualTo(SYNCED_AT);
    }

    @Test
    @DisplayName("실패한 쓰기는 기존 값을 바꾸지 않는다 — 지난 실행의 값 · source_snapshot_id 가 남는다")
    void writeFailureKeepsPreviousValues() {
        OfficialIngestResult first = processor.ingest(ariDraft("ari", 1), List.of(ariWeek("ND0715", 38, "12")), SYNCED_AT);
        OfficialIngestProcessor failing = processor(new FailingAfterUpsertPort(surveillanceAdapter, new IllegalStateException("boom")),
            snapshotAdapter);

        assertThatThrownBy(() -> failing.ingest(ariDraft("ari", 1), List.of(ariWeek("ND0715", 38, "99")), NEXT_SYNCED_AT))
            .isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM official_surveillance");
        assertThat((BigDecimal) row.get("METRIC_VALUE")).isEqualByComparingTo("12");
        assertThat(((Number) row.get("SOURCE_SNAPSHOT_ID")).longValue()).isEqualTo(first.snapshotId());
        assertThat(snapshotCount(IngestStatus.IMPORTED)).isEqualTo(1);
        assertThat(snapshotCount(IngestStatus.FAILED)).isEqualTo(1);
    }

    @Test
    @DisplayName("FAILED 기록마저 실패하면 원래 예외에 suppressed 로 붙고, 원래 예외가 나간다")
    void failureRecordingErrorIsSuppressed() {
        IllegalStateException boom = new IllegalStateException("upsert failed");
        IllegalStateException recordBoom = new IllegalStateException("snapshot insert failed");
        OfficialIngestProcessor failing = processor(new FailingAfterUpsertPort(surveillanceAdapter, boom),
            new FailingOnFailedSnapshotPort(snapshotAdapter, recordBoom));

        assertThatThrownBy(() -> failing.ingest(ariDraft("ari", 1), List.of(ariWeek("ND0715", 38, "1")), SYNCED_AT))
            .isSameAs(boom)
            .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(recordBoom));

        assertThat(snapshotCount(null)).isZero();
        assertThat(surveillanceCount()).isZero();
    }

    @Test
    @DisplayName("원천 불일치: 수집 기록과 다른 원천 · 프로그램 행이 있으면 행은 쓰지 않고 FAILED 기록(OFFICIAL_INGEST_001)만 남긴 채 SOURCE_MISMATCH")
    void rejectsSourceMismatchWithoutWriting() {
        OfficialRecord enteric = new OfficialRecord(OfficialSource.KDCA_SENTINEL, OfficialProgram.ENTERIC, "ND0715", "노로바이러스", null,
            OfficialMetric.CASE_COUNT, OfficialAgeGroup.ALL, OfficialRegionLevel.NATION, "00", "전국", OfficialPeriodType.WEEK, 2026, 38,
            LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19), BigDecimal.ONE);

        assertThatThrownBy(() -> processor.ingest(ariDraft("ari", 2), List.of(ariWeek("ND0715", 38, "1"), enteric), SYNCED_AT))
            .isInstanceOfSatisfying(OfficialIngestException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(OfficialIngestErrorCode.SOURCE_MISMATCH);
                assertThat(exception.getMessage()).startsWith("[OFFICIAL_INGEST_001]").contains("expected=KDCA_SENTINEL/ARI", "actual=KDCA_SENTINEL/ENTERIC");
            });

        assertOnlyFailedSnapshot(OfficialIngestErrorCode.SOURCE_MISMATCH);
    }

    @Test
    @DisplayName("자연키 중복: 한 적재에 같은 자연키가 둘이면 행은 쓰지 않고 FAILED 기록(OFFICIAL_INGEST_002)만 남긴 채 DUPLICATE_NATURAL_KEY — ON DUPLICATE KEY 가 조용히 접지 않게")
    void rejectsDuplicateNaturalKeyWithoutWriting() {
        assertThatThrownBy(() -> processor.ingest(ariDraft("ari", 2), List.of(ariWeek("ND0715", 38, "1"), ariWeek("ND0715", 38, "2")), SYNCED_AT))
            .isInstanceOfSatisfying(OfficialIngestException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OfficialIngestErrorCode.DUPLICATE_NATURAL_KEY));

        assertOnlyFailedSnapshot(OfficialIngestErrorCode.DUPLICATE_NATURAL_KEY);
    }

    @Test
    @DisplayName("검증 실패의 FAILED 기록마저 실패하면 검증 예외에 suppressed 로 붙고, 검증 예외가 나간다")
    void validationFailureRecordingErrorIsSuppressed() {
        IllegalStateException recordBoom = new IllegalStateException("snapshot insert failed");
        OfficialIngestProcessor failing = processor(surveillanceAdapter, new FailingOnFailedSnapshotPort(snapshotAdapter, recordBoom));

        assertThatThrownBy(() -> failing.ingest(ariDraft("ari", 2), List.of(ariWeek("ND0715", 38, "1"), ariWeek("ND0715", 38, "2")), SYNCED_AT))
            .isInstanceOfSatisfying(OfficialIngestException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(OfficialIngestErrorCode.DUPLICATE_NATURAL_KEY);
                assertThat(exception.getSuppressed()).containsExactly(recordBoom);
            });

        assertThat(snapshotCount(null)).isZero();
        assertThat(surveillanceCount()).isZero();
    }

    @Test
    @DisplayName("recordFailure: FAILED 기록 1행(imported_count 0, 넘긴 error_code · 시각)만 남고 원천 데이터는 쓰지 않는다")
    void recordFailureWritesOnlyFailedSnapshot() {
        processor.recordFailure(ariDraft("ari", 0), "SENTINEL_SCHEMA_CHANGED", SYNCED_AT);

        Map<String, Object> failed = jdbcTemplate.queryForMap("SELECT * FROM official_source_snapshot");
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("ERROR_CODE")).isEqualTo("SENTINEL_SCHEMA_CHANGED");
        assertThat(((Number) failed.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(((Timestamp) failed.get("CREATED_AT")).toLocalDateTime()).isEqualTo(SYNCED_AT);
        assertThat(surveillanceCount()).isZero();
    }

    /**
     * 스텝 트랜잭션({@code taskletTransactionManager} = 무자원)을 흉내 내 바깥을 감싼다. 무자원 매니저는 트랜잭션 동기화를 켜므로, 쓰기가 자체
     * 트랜잭션 없이 {@code JdbcTemplate} 을 쓰면 커넥션이 스레드에 묶인 채 다음 원천 호출을 기다린다.
     */
    @Test
    @DisplayName("스텝(무자원) 트랜잭션 안에서 불러도 ingest · recordFailure 뒤 DataSource 커넥션이 스레드에 묶여 있지 않고, 쓴 것은 커밋돼 있다")
    void doesNotBindConnectionUnderResourcelessStepTransaction() {
        boolean[] bound = {true, true};

        new TransactionTemplate(new ResourcelessTransactionManager()).executeWithoutResult(status -> {
            processor.ingest(ariDraft("ari", 1), List.of(ariWeek("ND0715", 38, "1")), SYNCED_AT);
            bound[0] = TransactionSynchronizationManager.hasResource(dataSource);
            processor.recordFailure(ariDraft("enteric", 0), "SENTINEL_SCHEMA_CHANGED", SYNCED_AT);
            bound[1] = TransactionSynchronizationManager.hasResource(dataSource);
        });

        assertThat(bound).containsExactly(false, false);
        assertThat(snapshotCount(IngestStatus.IMPORTED)).isEqualTo(1);
        assertThat(snapshotCount(IngestStatus.FAILED)).isEqualTo(1);
        assertThat(surveillanceCount()).isEqualTo(1);
    }

    private OfficialIngestProcessor processor(OfficialSurveillanceBulkPort surveillancePort, OfficialSourceSnapshotBulkPort snapshotPort) {
        return new OfficialIngestProcessor(surveillancePort, snapshotPort, new SnowflakeIdGenerator(0, 1), new DataSourceTransactionManager(dataSource));
    }

    /** 행 0 · IMPORTED 0 · FAILED 1(error_code = 그 에러코드의 code, imported_count 0, created_at = syncedAt). */
    private void assertOnlyFailedSnapshot(OfficialIngestErrorCode errorCode) {
        assertThat(surveillanceCount()).isZero();
        assertThat(snapshotCount(IngestStatus.IMPORTED)).isZero();
        Map<String, Object> failed = jdbcTemplate.queryForMap("SELECT * FROM official_source_snapshot");
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("ERROR_CODE")).isEqualTo(errorCode.getCode());
        assertThat(((Number) failed.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(((Timestamp) failed.get("CREATED_AT")).toLocalDateTime()).isEqualTo(SYNCED_AT);
    }

    private int surveillanceCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class);
    }

    /** {@code status} 가 null 이면 전체. */
    private int snapshotCount(IngestStatus status) {
        if (status == null) {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot", Integer.class);
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE status = ?", Integer.class, status.name());
    }

    /** 실제로 다 쓴 뒤 실패한다 — 쓴 행이 롤백되는지 본다. */
    private record FailingAfterUpsertPort(OfficialSurveillanceBulkPort delegate, RuntimeException failure) implements OfficialSurveillanceBulkPort {

        @Override
        public int upsertAll(List<IdentifiedOfficialRecord> rows, long sourceSnapshotId, LocalDateTime syncedAt) {
            delegate.upsertAll(rows, sourceSnapshotId, syncedAt);
            throw failure;
        }
    }

    /** IMPORTED 는 실제로 쓰고, FAILED 기록에서 실패한다. */
    private record FailingOnFailedSnapshotPort(OfficialSourceSnapshotBulkPort delegate, RuntimeException failure)
        implements OfficialSourceSnapshotBulkPort {

        @Override
        public void insert(OfficialSourceSnapshot snapshot) {
            if (snapshot.status() == IngestStatus.FAILED) {
                throw failure;
            }
            delegate.insert(snapshot);
        }
    }
}
