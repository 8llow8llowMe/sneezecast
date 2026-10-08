package com.sneezecast.domainlayer.sentinelimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sneezecast.domainlayer.official.OfficialFixtures;
import com.sneezecast.domainlayer.official.adapter.out.persistence.JdbcOfficialSourceSnapshotBulkAdapter;
import com.sneezecast.domainlayer.official.adapter.out.persistence.JdbcOfficialSurveillanceBulkAdapter;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestErrorCode;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestException;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSourceSnapshotBulkPort;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceBulkPort;
import com.sneezecast.domainlayer.official.application.service.processor.OfficialIngestProcessor;
import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import com.sneezecast.domainlayer.official.domain.model.KdcaWeek;
import com.sneezecast.domainlayer.sentinelimport.application.command.SentinelImportCommand;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelCallBudget;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelImportResult;
import com.sneezecast.domainlayer.sentinelimport.application.port.out.SentinelSourcePort;
import com.sneezecast.domainlayer.sentinelimport.application.service.processor.SentinelRecordProcessor;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelFetch;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelProgram;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import com.sneezecast.global.properties.SentinelImportProperties;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원천은 Mockito 가짜, 쓰기는 <b>실제</b> {@link OfficialIngestProcessor} · JDBC 어댑터 · {@link DataSourceTransactionManager}(H2 MySQL 모드)로
 * 계획 순서 · 요청별 격리 · 실행 중단 · 적재 이력을 본다.
 *
 * <p>기준일 2026-10-09(금)은 2026년 41주라 계획은 4건이다 — 급성호흡기 34 ~ 41주 → 장관 34 ~ 41주 → 인플루엔자 2026–2027 → 2025–2026.
 * 가짜 원천은 실제 어댑터처럼 요청마다 화면 + 데이터 두 번 예산을 쓴다 (4건 = 8회).
 */
class SentinelImportFacadeTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 9);
    private static final int PLANNED = 4;
    private static final int CALLS_PER_REQUEST = 2;
    private static final LocalDateTime RUN_AT = LocalDateTime.of(2026, 10, 9, 6, 0);
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 10, 9, 6, 0, 3);
    private static final Clock CLOCK = Clock.fixed(SYNCED_AT.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private static final String SHA256 = "d".repeat(64);
    private static final SentinelRequest ARI = SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 34), new KdcaWeek(2026, 41));
    private static final SentinelRequest ENTERIC = SentinelRequest.weeks(SentinelProgram.ENTERIC, new KdcaWeek(2026, 34), new KdcaWeek(2026, 41));
    private static final SentinelRequest CURRENT_SEASON = SentinelRequest.season(2026);
    private static final SentinelRequest PREVIOUS_SEASON = SentinelRequest.season(2025);
    private static final String ARI_KEY = "sentinel:ari:2026-34~2026-41";
    private static final String ENTERIC_KEY = "sentinel:enteric:2026-34~2026-41";
    private static final String CURRENT_SEASON_KEY = "sentinel:influenza:2026-2027";
    private static final String PREVIOUS_SEASON_KEY = "sentinel:influenza:2025-2026";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SentinelSourcePort sourcePort;
    private JdbcOfficialSurveillanceBulkAdapter surveillanceAdapter;
    private JdbcOfficialSourceSnapshotBulkAdapter snapshotAdapter;
    /** 테스트 안의 모든 프로세서가 나눠 쓴다 — 같은 worker 의 생성기 둘이 같은 ms 에 같은 id 를 만들면 PK 가 겹친다. */
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @BeforeEach
    void setUp() {
        snowflakeIdGenerator = new SnowflakeIdGenerator(0, 1);
        dataSource = OfficialFixtures.h2DataSource("sentinel-facade");
        jdbcTemplate = new JdbcTemplate(dataSource);
        surveillanceAdapter = new JdbcOfficialSurveillanceBulkAdapter(jdbcTemplate);
        snapshotAdapter = new JdbcOfficialSourceSnapshotBulkAdapter(jdbcTemplate);
        sourcePort = mock(SentinelSourcePort.class);
        willAnswer(invocation -> {
            consume(invocation);
            return pathogenFetch(invocation.getArgument(0));
        }).given(sourcePort).fetchPathogens(any(), any());
        willAnswer(invocation -> {
            consume(invocation);
            return influenzaFetch(invocation.getArgument(0));
        }).given(sourcePort).fetchInfluenza(any(), any());
    }

    @Test
    @DisplayName("H2: 계획 4건이 IMPORTED 4행으로 남고, 병원체는 신고 수 · 전국 · 질병관리청 주차, 인플루엔자는 ILI · 연령대 · 분율로 들어간다")
    void importsWholePlan() {
        SentinelImportResult result = facade(12).importSentinel(new SentinelImportCommand(BASE_DATE, RUN_AT));

        // 병원체 요청마다 3행(그중 1행은 값 null, 집계 중 2칸), 현재 절기 2행, 지난 절기 1행.
        assertThat(result).isEqualTo(new SentinelImportResult(BASE_DATE, PLANNED, 3 + 3 + 2 + 1, PLANNED * CALLS_PER_REQUEST, 2, 4));
        assertThat(snapshotCount("IMPORTED")).isEqualTo(PLANNED);
        assertThat(snapshotCount("FAILED")).isZero();
        verify(sourcePort).fetchPathogens(eq(ARI), any());
        verify(sourcePort).fetchPathogens(eq(ENTERIC), any());
        verify(sourcePort).fetchInfluenza(eq(CURRENT_SEASON), any());
        verify(sourcePort).fetchInfluenza(eq(PREVIOUS_SEASON), any());

        Map<String, Object> ariSnapshot = snapshot(ARI_KEY);
        assertThat(ariSnapshot.get("SOURCE")).isEqualTo("KDCA_SENTINEL");
        assertThat(ariSnapshot.get("PROGRAM")).isEqualTo("ARI");
        assertThat(ariSnapshot.get("CHANNEL")).isEqualTo("PORTAL_JSON");
        assertThat(ariSnapshot.get("CONTENT_SHA256")).isEqualTo(SHA256);
        assertThat(((Number) ariSnapshot.get("BYTE_LENGTH")).intValue()).isEqualTo(4096);
        // row_count 는 응답 data 의 행 수(주 수), imported_count 는 쓴 행 수다.
        assertThat(((Number) ariSnapshot.get("ROW_COUNT")).intValue()).isEqualTo(2);
        assertThat(((Number) ariSnapshot.get("IMPORTED_COUNT")).intValue()).isEqualTo(3);
        assertThat(((Timestamp) ariSnapshot.get("RUN_STARTED_AT")).toLocalDateTime()).isEqualTo(RUN_AT);
        assertThat(snapshot(ENTERIC_KEY).get("PROGRAM")).isEqualTo("ENTERIC");
        assertThat(snapshot(CURRENT_SEASON_KEY).get("PROGRAM")).isEqualTo("INFLUENZA_ILI");

        Map<String, Object> total = jdbcTemplate.queryForMap(
            "SELECT * FROM official_surveillance WHERE program = 'ENTERIC' AND disease_key = 'TOTAL' AND period_week = 39");
        assertThat(total.get("SOURCE")).isEqualTo("KDCA_SENTINEL");
        assertThat(total.get("DISEASE_NAME")).isEqualTo("계");
        assertThat(total.get("DISEASE_GROUP")).isEqualTo("계");
        assertThat(total.get("METRIC")).isEqualTo("CASE_COUNT");
        assertThat(total.get("AGE_GROUP")).isEqualTo("ALL");
        assertThat(total.get("REGION_LEVEL")).isEqualTo("NATION");
        assertThat(total.get("REGION_CODE")).isEqualTo("00");
        assertThat(total.get("REGION_NAME")).isEqualTo("전국");
        assertThat(total.get("PERIOD_TYPE")).isEqualTo("WEEK");
        assertThat(((Date) total.get("PERIOD_START")).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(((Date) total.get("PERIOD_END")).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat((BigDecimal) total.get("METRIC_VALUE")).isEqualByComparingTo("120");
        assertThat(jdbcTemplate.queryForObject("SELECT metric_value FROM official_surveillance WHERE program = 'ARI' AND period_week = 40",
            BigDecimal.class)).isNull();

        Map<String, Object> ili = jdbcTemplate.queryForMap(
            "SELECT * FROM official_surveillance WHERE program = 'INFLUENZA_ILI' AND period_year = 2026 AND period_week = 39 AND age_group = 'AGE_65_PLUS'");
        assertThat(ili.get("DISEASE_KEY")).isEqualTo("ILI");
        assertThat(ili.get("DISEASE_NAME")).isEqualTo("인플루엔자 의사환자 분율");
        assertThat(ili.get("DISEASE_GROUP")).isNull();
        assertThat(ili.get("METRIC")).isEqualTo("ILI_PER_1000");
        assertThat(ili.get("REGION_CODE")).isEqualTo("00");
        assertThat(((Date) ili.get("PERIOD_START")).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat((BigDecimal) ili.get("METRIC_VALUE")).isEqualByComparingTo("1.25");
        assertThat(((Number) ili.get("SOURCE_SNAPSHOT_ID")).longValue()).isEqualTo(((Number) snapshot(CURRENT_SEASON_KEY).get("ID")).longValue());
        // 지난 절기의 35주는 끝 연도(2026)로 들어간다.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM official_surveillance WHERE program = 'INFLUENZA_ILI' AND period_year = 2026 AND period_week = 35", Integer.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("H2: 같은 실행을 다시 돌려도 행 수는 그대로(멱등 upsert)이고 적재 이력만 쌓인다")
    void rerunIsIdempotent() {
        SentinelImportFacade facade = facade(12);
        facade.importSentinel(new SentinelImportCommand(BASE_DATE, RUN_AT));
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class);

        facade.importSentinel(new SentinelImportCommand(BASE_DATE, RUN_AT));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class)).isEqualTo(rows).isEqualTo(9);
        assertThat(snapshotCount("IMPORTED")).isEqualTo(2 * PLANNED);
    }

    /** 스텝 트랜잭션(무자원)을 흉내 내 바깥을 감싼다 — 앞 요청의 쓰기가 커넥션을 스레드에 묶어 두면 다음 원천 호출 내내 쥔다. */
    @Test
    @DisplayName("H2: 스텝 트랜잭션 안에서도 모든 원천 호출은 DataSource 커넥션이 스레드에 묶이지 않은 채 일어난다")
    void sourceIsCalledOutsideTransaction() {
        List<Boolean> bound = new ArrayList<>();
        willAnswer(invocation -> {
            bound.add(TransactionSynchronizationManager.hasResource(dataSource));
            consume(invocation);
            return pathogenFetch(invocation.getArgument(0));
        }).given(sourcePort).fetchPathogens(any(), any());
        willAnswer(invocation -> {
            bound.add(TransactionSynchronizationManager.hasResource(dataSource));
            consume(invocation);
            return influenzaFetch(invocation.getArgument(0));
        }).given(sourcePort).fetchInfluenza(any(), any());

        SentinelImportResult result = new TransactionTemplate(new ResourcelessTransactionManager())
            .execute(status -> facade(12).importSentinel(new SentinelImportCommand(BASE_DATE, RUN_AT)));

        assertThat(result.requests()).isEqualTo(PLANNED);
        assertThat(bound).hasSize(PLANNED).containsOnly(false);
    }

    @Test
    @DisplayName("0행 응답(시작 전 절기)도 정상이다 — IMPORTED 이력 한 행(imported 0)을 남기고 Job 은 실패하지 않는다")
    void recordsEmptyResponseAsImported() {
        willAnswer(invocation -> {
            consume(invocation);
            return fetch(List.<SentinelIliRow>of(), 0, 0, 0);
        }).given(sourcePort).fetchInfluenza(eq(CURRENT_SEASON), any());

        SentinelImportResult result = facade(12).importSentinel(new SentinelImportCommand(BASE_DATE, RUN_AT));

        assertThat(result.requests()).isEqualTo(PLANNED);
        Map<String, Object> empty = snapshot(CURRENT_SEASON_KEY);
        assertThat(empty.get("STATUS")).isEqualTo("IMPORTED");
        assertThat(((Number) empty.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(((Number) empty.get("ROW_COUNT")).intValue()).isZero();
        assertThat(empty.get("CONTENT_SHA256")).isEqualTo(SHA256);
    }

    @Test
    @DisplayName("인플루엔자 형식 변경(SCHEMA_CHANGED)은 그 요청만 FAILED 이고 급성호흡기 · 장관 · 지난 절기는 반영한 뒤, 끝에 RUN_FAILED 로 실패한다")
    void isolatesSchemaChange() {
        willThrow(sourceFailure(SentinelImportErrorCode.SCHEMA_CHANGED)).given(sourcePort).fetchInfluenza(eq(CURRENT_SEASON), any());

        assertRunFailed(facade(12), exception -> {
            assertThat(exception.getMessage()).contains("planned=4", "imported=3", "failed=1", "notAttempted=0", "aborted=false", "abortedBy=-",
                "failedRequestKeys=[" + CURRENT_SEASON_KEY + "]");
            assertThat(exception.getCause()).isNull();
        });

        assertThat(snapshotCount("IMPORTED")).isEqualTo(PLANNED - 1);
        Map<String, Object> failed = snapshot(CURRENT_SEASON_KEY);
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("ERROR_CODE")).isEqualTo("SENTINEL_IMPORT_005");
        assertThat(failed.get("SOURCE")).isEqualTo("KDCA_SENTINEL");
        assertThat(failed.get("PROGRAM")).isEqualTo("INFLUENZA_ILI");
        assertThat(failed.get("CHANNEL")).isEqualTo("PORTAL_JSON");
        assertThat(failed.get("CONTENT_SHA256")).isNull();
        assertThat(((Number) failed.get("BYTE_LENGTH")).intValue()).isZero();
        assertThat(((Number) failed.get("ROW_COUNT")).intValue()).isZero();
        assertThat(((Number) failed.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(((Timestamp) failed.get("RUN_STARTED_AT")).toLocalDateTime()).isEqualTo(RUN_AT);
        // 이후 요청(지난 절기)도 그대로 불렀다.
        verify(sourcePort).fetchInfluenza(eq(PREVIOUS_SEASON), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SentinelImportErrorCode.class, names = {"PORTAL_HTTP_ERROR", "PORTAL_CALL_FAILED", "PORTAL_SESSION_MISSING", "RESPONSE_INVALID"})
    @DisplayName("포털 오류 · 호출 실패 · 세션 없음 · 응답 해석 실패는 그 요청만 FAILED 이고 나머지 요청은 계속 부른다")
    void isolatesSourceError(SentinelImportErrorCode errorCode) {
        willThrow(sourceFailure(errorCode)).given(sourcePort).fetchPathogens(eq(ARI), any());

        assertRunFailed(facade(12), exception -> assertThat(exception.getMessage()).contains(
            "imported=3", "failed=1", "notAttempted=0", "aborted=false", "failedRequestKeys=[" + ARI_KEY + "]"));

        assertThat(snapshot(ARI_KEY).get("ERROR_CODE")).isEqualTo(errorCode.getCode());
        verify(sourcePort, times(2)).fetchPathogens(any(), any());
        verify(sourcePort, times(2)).fetchInfluenza(any(), any());
    }

    @Test
    @DisplayName("시각 출처는 하나다 — 성공 · 실패 이력의 created_at 과 모든 행의 synced_at 이 실행 시작 때 정한 같은 시각이다")
    void usesSingleSyncedAt() {
        willThrow(sourceFailure(SentinelImportErrorCode.PORTAL_HTTP_ERROR)).given(sourcePort).fetchPathogens(eq(ENTERIC), any());

        assertRunFailed(facade(12), exception -> assertThat(exception.getMessage()).contains("failed=1"));

        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT created_at FROM official_source_snapshot", Timestamp.class))
            .containsExactly(Timestamp.valueOf(SYNCED_AT));
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT synced_at FROM official_surveillance", Timestamp.class))
            .containsExactly(Timestamp.valueOf(SYNCED_AT));
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT run_started_at FROM official_source_snapshot", Timestamp.class))
            .containsExactly(Timestamp.valueOf(RUN_AT));
    }

    @Test
    @DisplayName("실제 예산: 실행당 호출 상한 5 면 세 번째 요청의 데이터 호출이 막혀 FAILED(SENTINEL_IMPORT_010)로 남고 실행이 멈춘다")
    void budgetStopsRun() {
        assertRunFailed(facade(5), exception -> {
            assertThat(exception.getMessage()).contains("planned=4", "imported=2", "failed=1", "notAttempted=1", "aborted=true",
                "abortedBy=SENTINEL_IMPORT_010", "failedRequestKeys=[" + CURRENT_SEASON_KEY + "]");
            assertThat(exception.getCause()).isInstanceOfSatisfying(SentinelImportException.class,
                cause -> assertThat(cause.getErrorCode()).isEqualTo(SentinelImportErrorCode.REQUEST_BUDGET_EXCEEDED));
        });

        verify(sourcePort, times(1)).fetchInfluenza(any(), any());
        verify(sourcePort, never()).fetchInfluenza(eq(PREVIOUS_SEASON), any());
        assertThat(snapshot(CURRENT_SEASON_KEY).get("ERROR_CODE")).isEqualTo("SENTINEL_IMPORT_010");
        assertThat(snapshotCount("IMPORTED")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE request_key = ?", Integer.class,
            PREVIOUS_SEASON_KEY)).isZero();
    }

    @Test
    @DisplayName("쓰기 실패(DataAccessException)는 프로세서가 FAILED(OFFICIAL_INGEST_003)를 남기고, 실행은 멈춘다 — DB 문제면 이후 쓰기도 실패한다")
    void abortsRunOnWriteFailure() {
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        SentinelImportFacade facade = facade(12, new FailingSurveillancePort(dbDown), snapshotAdapter);

        assertRunFailed(facade, exception -> {
            assertThat(exception.getMessage()).contains("imported=0", "failed=1", "notAttempted=3", "aborted=true", "abortedBy=OFFICIAL_INGEST_003");
            assertThat(exception.getCause()).isSameAs(dbDown);
        });

        verify(sourcePort, times(1)).fetchPathogens(any(), any());
        verify(sourcePort, never()).fetchInfluenza(any(), any());
        assertThat(snapshot(ARI_KEY).get("ERROR_CODE")).isEqualTo("OFFICIAL_INGEST_003");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class)).isZero();
    }

    @Test
    @DisplayName("원천 실패의 FAILED 이력마저 못 쓰면(DB 장애) 실행을 멈춘다")
    void abortsRunWhenSourceFailureCannotBeRecorded() {
        willThrow(sourceFailure(SentinelImportErrorCode.PORTAL_CALL_FAILED)).given(sourcePort).fetchPathogens(eq(ARI), any());
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        SentinelImportFacade facade = facade(12, surveillanceAdapter, snapshot -> {
            throw dbDown;
        });

        assertRunFailed(facade, exception -> {
            assertThat(exception.getMessage()).contains("failed=1", "notAttempted=3", "aborted=true", "abortedBy=OFFICIAL_INGEST_003");
            assertThat(exception.getCause()).isSameAs(dbDown);
            assertThat(dbDown.getSuppressed()).singleElement().isInstanceOf(SentinelImportException.class);
        });
        verify(sourcePort, times(1)).fetchPathogens(any(), any());
        verify(sourcePort, never()).fetchInfluenza(any(), any());
    }

    @Test
    @DisplayName("검증 실패(같은 자연키 두 번)는 그 요청만 FAILED(OFFICIAL_INGEST_002)이고 나머지는 계속 반영한다")
    void continuesAfterValidationFailure() {
        givenDuplicateRowsFor(ENTERIC);

        assertRunFailed(facade(12), exception -> assertThat(exception.getMessage()).contains(
            "imported=3", "failed=1", "notAttempted=0", "aborted=false", "failedRequestKeys=[" + ENTERIC_KEY + "]"));

        assertThat(snapshot(ENTERIC_KEY).get("ERROR_CODE")).isEqualTo("OFFICIAL_INGEST_002");
        assertThat(snapshotCount("IMPORTED")).isEqualTo(PLANNED - 1);
    }

    @Test
    @DisplayName("검증 실패의 FAILED 이력마저 못 쓰면(프로세서가 suppressed 로 붙인다) DB 문제로 보고 실행을 멈춘다")
    void abortsRunWhenValidationFailureCannotBeRecorded() {
        givenDuplicateRowsFor(ARI);
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        SentinelImportFacade facade = facade(12, surveillanceAdapter, snapshot -> {
            throw dbDown;
        });

        assertRunFailed(facade, exception -> {
            assertThat(exception.getMessage()).contains("imported=0", "failed=1", "notAttempted=3", "aborted=true", "abortedBy=OFFICIAL_INGEST_003",
                "failedRequestKeys=[" + ARI_KEY + "]");
            assertThat(exception.getCause()).isInstanceOfSatisfying(OfficialIngestException.class, cause -> {
                assertThat(cause.getErrorCode()).isEqualTo(OfficialIngestErrorCode.DUPLICATE_NATURAL_KEY);
                assertThat(cause.getSuppressed()).containsExactly(dbDown);
            });
        });
        verify(sourcePort, times(1)).fetchPathogens(any(), any());
        verify(sourcePort, never()).fetchInfluenza(any(), any());
    }

    @Test
    @DisplayName("변환 실패(52주인 2025년의 53주)는 그 요청만 FAILED(RESPONSE_INVALID)이고 나머지는 계속 반영한다")
    void treatsConversionFailureAsResponseInvalid() {
        willAnswer(invocation -> {
            consume(invocation);
            return fetch(List.of(new SentinelIliRow(2025, 53, OfficialAgeGroup.AGE_0, BigDecimal.ONE)), 1, 0, 0);
        }).given(sourcePort).fetchInfluenza(eq(PREVIOUS_SEASON), any());

        assertRunFailed(facade(12), exception -> assertThat(exception.getMessage()).contains(
            "imported=3", "failed=1", "aborted=false", "failedRequestKeys=[" + PREVIOUS_SEASON_KEY + "]"));

        assertThat(snapshot(PREVIOUS_SEASON_KEY).get("ERROR_CODE")).isEqualTo("SENTINEL_IMPORT_004");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance WHERE period_year = 2025", Integer.class)).isZero();
    }

    @Test
    @DisplayName("기준일을 지정하면(백필) 그 날이 든 주를 끝으로 받는다 — 2026-06-05(23주)는 16 ~ 23주와 2025–2026 절기 3건이다")
    void plansFromBaseDate() {
        SentinelImportResult result = facade(12).importSentinel(new SentinelImportCommand(LocalDate.of(2026, 6, 5), RUN_AT));

        assertThat(result.requests()).isEqualTo(3);
        verify(sourcePort).fetchPathogens(eq(SentinelRequest.weeks(SentinelProgram.ARI, new KdcaWeek(2026, 16), new KdcaWeek(2026, 23))), any());
        verify(sourcePort).fetchInfluenza(eq(PREVIOUS_SEASON), any());
        verify(sourcePort, never()).fetchInfluenza(eq(CURRENT_SEASON), any());
        assertThat(snapshot("sentinel:enteric:2026-16~2026-23").get("STATUS")).isEqualTo("IMPORTED");
    }

    private SentinelImportFacade facade(int maxCallsPerRun) {
        return facade(maxCallsPerRun, surveillanceAdapter, snapshotAdapter);
    }

    private SentinelImportFacade facade(int maxCallsPerRun, OfficialSurveillanceBulkPort surveillancePort, OfficialSourceSnapshotBulkPort snapshotPort) {
        OfficialIngestProcessor processor = new OfficialIngestProcessor(surveillancePort, snapshotPort, snowflakeIdGenerator,
            new DataSourceTransactionManager(dataSource));
        return new SentinelImportFacade(sourcePort, new SentinelRecordProcessor(), processor, new SentinelImportProperties(8, maxCallsPerRun), CLOCK);
    }

    private void givenDuplicateRowsFor(SentinelRequest request) {
        SentinelPathogenRow row = new SentinelPathogenRow(2026, 39, "HRV", "리노바이러스", "바이러스", BigDecimal.ONE);
        willAnswer(invocation -> {
            consume(invocation);
            return fetch(List.of(row, row), 1, 0, 0);
        }).given(sourcePort).fetchPathogens(eq(request), any());
    }

    private static void assertRunFailed(SentinelImportFacade facade, Consumer<SentinelImportException> requirements) {
        assertThatThrownBy(() -> facade.importSentinel(new SentinelImportCommand(BASE_DATE, RUN_AT)))
            .isInstanceOfSatisfying(SentinelImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(SentinelImportErrorCode.RUN_FAILED);
                assertThat(exception.getMessage()).startsWith("[SENTINEL_IMPORT_020]");
                requirements.accept(exception);
            });
    }

    /** 메시지 자리 표시자가 코드마다 1 ~ 2개라 넉넉히 넘긴다 (남는 인자는 무시된다). */
    private static SentinelImportException sourceFailure(SentinelImportErrorCode errorCode) {
        return new SentinelImportException(errorCode, "op", "x", "x");
    }

    /** 실제 어댑터처럼 화면 · 데이터 호출 직전마다 예산을 쓴다. */
    private static void consume(InvocationOnMock invocation) {
        SentinelCallBudget budget = invocation.getArgument(1);
        budget.consume();
        budget.consume();
    }

    /** 39 · 40주 두 행(주) — 39주 계 · 리노바이러스, 40주 리노바이러스는 값이 비었고 나머지 두 칸은 집계 중이다. */
    private static SentinelFetch<SentinelPathogenRow> pathogenFetch(SentinelRequest request) {
        return fetch(List.of(
            new SentinelPathogenRow(2026, 39, "TOTAL", "계", "계", new BigDecimal("120")),
            new SentinelPathogenRow(2026, 39, "HRV", "리노바이러스", "바이러스", new BigDecimal("30")),
            new SentinelPathogenRow(2026, 40, "HRV", "리노바이러스", "바이러스", null)), 2, 1, 2);
    }

    /** 현재 절기는 39주 두 연령대, 지난 절기는 끝 연도 35주 한 칸. */
    private static SentinelFetch<SentinelIliRow> influenzaFetch(SentinelRequest request) {
        if (request.seasonStartYear() == 2026) {
            return fetch(List.of(
                new SentinelIliRow(2026, 39, OfficialAgeGroup.AGE_0, new BigDecimal("3.5")),
                new SentinelIliRow(2026, 39, OfficialAgeGroup.AGE_65_PLUS, new BigDecimal("1.25"))), 7, 0, 0);
        }
        return fetch(List.of(new SentinelIliRow(request.seasonEndYear(), 35, OfficialAgeGroup.AGE_19_49, new BigDecimal("4.1"))), 7, 0, 0);
    }

    private static <T> SentinelFetch<T> fetch(List<T> rows, int rawRowCount, int nullValueCount, int pendingCount) {
        return new SentinelFetch<>(rows, SHA256, 4096, rawRowCount, CALLS_PER_REQUEST, nullValueCount, pendingCount);
    }

    private Map<String, Object> snapshot(String requestKey) {
        return jdbcTemplate.queryForMap("SELECT * FROM official_source_snapshot WHERE request_key = ?", requestKey);
    }

    private int snapshotCount(String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE status = ?", Integer.class, status);
    }

    /** 쓰기마다 실패한다 — DB 장애를 흉내 낸다. */
    private record FailingSurveillancePort(RuntimeException failure) implements OfficialSurveillanceBulkPort {

        @Override
        public int upsertAll(List<IdentifiedOfficialRecord> rows, long sourceSnapshotId, LocalDateTime syncedAt) {
            throw failure;
        }
    }
}
