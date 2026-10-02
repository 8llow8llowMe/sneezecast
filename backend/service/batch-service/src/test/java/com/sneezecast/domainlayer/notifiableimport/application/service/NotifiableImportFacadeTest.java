package com.sneezecast.domainlayer.notifiableimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sneezecast.domainlayer.notifiableimport.application.command.NotifiableImportCommand;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.application.model.KdcaCallBudget;
import com.sneezecast.domainlayer.notifiableimport.application.model.NotifiableImportResult;
import com.sneezecast.domainlayer.notifiableimport.application.port.out.NotifiableSourcePort;
import com.sneezecast.domainlayer.notifiableimport.application.service.processor.NotifiableRecordProcessor;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableFetch;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
import com.sneezecast.domainlayer.official.OfficialFixtures;
import com.sneezecast.domainlayer.official.adapter.out.persistence.JdbcOfficialSourceSnapshotBulkAdapter;
import com.sneezecast.domainlayer.official.adapter.out.persistence.JdbcOfficialSurveillanceBulkAdapter;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestErrorCode;
import com.sneezecast.domainlayer.official.application.exception.OfficialIngestException;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSourceSnapshotBulkPort;
import com.sneezecast.domainlayer.official.application.port.out.OfficialSurveillanceBulkPort;
import com.sneezecast.domainlayer.official.application.service.processor.OfficialIngestProcessor;
import com.sneezecast.domainlayer.official.domain.model.IdentifiedOfficialRecord;
import com.sneezecast.global.properties.NotifiableImportProperties;
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
 * <p>시도 2개(01 · 02)라 계획은 주별 2 + 시도 연별 2(연도) × 2(지표) × 2(시도) = 10건이다. 순서는 주별 2026 → 2025 → 시도 2026(발생 수 01 · 02,
 * 10만 명당 01 · 02) → 2025 같은 순.
 */
class NotifiableImportFacadeTest {

    private static final int CURRENT_YEAR = 2026;
    private static final int PLANNED = 10;
    private static final LocalDateTime RUN_AT = LocalDateTime.of(2026, 10, 6, 5, 0);
    private static final LocalDateTime SYNCED_AT = LocalDateTime.of(2026, 10, 6, 5, 0, 7);
    private static final Clock CLOCK = Clock.fixed(SYNCED_AT.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private static final List<String> SIDO_CODES = List.of("01", "02");
    private static final String SHA256 = "b".repeat(64);
    /** 계획의 세 번째 요청 — 주별 둘 다음 첫 시도 요청. */
    private static final String THIRD_REQUEST_KEY = "notifiable:region:count:2026:sido=01";
    private static final Map<String, String> SIDO_NAMES = Map.of("01", "서울", "02", "부산", "03", "대구", "04", "인천", "05", "광주", "06", "대전");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private NotifiableSourcePort sourcePort;
    private JdbcOfficialSurveillanceBulkAdapter surveillanceAdapter;
    private JdbcOfficialSourceSnapshotBulkAdapter snapshotAdapter;
    /** 테스트 안의 모든 프로세서가 나눠 쓴다 — 같은 worker 의 생성기 둘이 같은 ms 에 같은 id 를 만들면 PK 가 겹친다. */
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @BeforeEach
    void setUp() {
        snowflakeIdGenerator = new SnowflakeIdGenerator(0, 1);
        dataSource = OfficialFixtures.h2DataSource("notifiable-facade");
        jdbcTemplate = new JdbcTemplate(dataSource);
        surveillanceAdapter = new JdbcOfficialSurveillanceBulkAdapter(jdbcTemplate);
        snapshotAdapter = new JdbcOfficialSourceSnapshotBulkAdapter(jdbcTemplate);
        sourcePort = mock(NotifiableSourcePort.class);
        // 실제 어댑터처럼 HTTP 호출 직전마다 예산을 쓴다.
        willAnswer(invocation -> {
            consume(invocation, 1);
            return weeklyFetch(invocation.getArgument(0));
        }).given(sourcePort).fetchWeekly(anyInt(), any());
        willAnswer(invocation -> {
            consume(invocation, 3);
            return regionFetch(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).given(sourcePort).fetchRegion(anyInt(), any(), anyString(), any());
    }

    @Test
    @DisplayName("H2: 계획 10건이 IMPORTED 10행으로 남고, 주별은 WEEK · 전국 · 질병관리청 주차 기간, 시도는 YEAR · 주차 0 · 1/1 ~ 12/31 로 들어간다")
    void importsWholePlan() {
        NotifiableImportResult result = facade(100).importNotifiable(new NotifiableImportCommand(CURRENT_YEAR, RUN_AT));

        // 주별: 연도마다 2행(그중 1행은 값 null), 시도: 요청마다 1행.
        assertThat(result).isEqualTo(new NotifiableImportResult(CURRENT_YEAR, PLANNED, 2 * 2 + 8, PLANNED, 2));
        assertThat(snapshotCount("IMPORTED")).isEqualTo(PLANNED);
        assertThat(snapshotCount("FAILED")).isZero();
        Map<String, Object> weeklySnapshot = snapshot("notifiable:periodBasic:week:2026");
        assertThat(weeklySnapshot.get("SOURCE")).isEqualTo("KDCA_NOTIFIABLE");
        assertThat(weeklySnapshot.get("PROGRAM")).isEqualTo("NOTIFIABLE");
        assertThat(weeklySnapshot.get("CHANNEL")).isEqualTo("OPEN_API");
        assertThat(weeklySnapshot.get("CONTENT_SHA256")).isEqualTo(SHA256);
        assertThat(((Number) weeklySnapshot.get("BYTE_LENGTH")).intValue()).isEqualTo(2048);
        // row_count 는 받은 원천 행 수(계 행 포함), imported_count 는 쓴 행 수다.
        assertThat(((Number) weeklySnapshot.get("ROW_COUNT")).intValue()).isEqualTo(3);
        assertThat(((Number) weeklySnapshot.get("IMPORTED_COUNT")).intValue()).isEqualTo(2);
        assertThat(((Timestamp) weeklySnapshot.get("RUN_STARTED_AT")).toLocalDateTime()).isEqualTo(RUN_AT);

        Map<String, Object> weekly = jdbcTemplate.queryForMap(
            "SELECT * FROM official_surveillance WHERE period_type = 'WEEK' AND period_year = 2026 AND period_week = 38");
        assertThat(weekly.get("DISEASE_KEY")).isEqualTo("엠폭스");
        assertThat(weekly.get("DISEASE_NAME")).isEqualTo("@엠폭스");
        assertThat(weekly.get("METRIC")).isEqualTo("CASE_COUNT");
        assertThat(weekly.get("AGE_GROUP")).isEqualTo("ALL");
        assertThat(weekly.get("REGION_LEVEL")).isEqualTo("NATION");
        assertThat(weekly.get("REGION_CODE")).isEqualTo("00");
        assertThat(weekly.get("REGION_NAME")).isEqualTo("전국");
        assertThat(((Date) weekly.get("PERIOD_START")).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(((Date) weekly.get("PERIOD_END")).toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(weekly.get("METRIC_VALUE")).isNull();
        assertThat(((Number) weekly.get("SOURCE_SNAPSHOT_ID")).longValue()).isEqualTo(((Number) weeklySnapshot.get("ID")).longValue());

        Map<String, Object> region = jdbcTemplate.queryForMap(
            "SELECT * FROM official_surveillance WHERE period_type = 'YEAR' AND period_year = 2025 AND region_code = '02' AND metric = 'INCIDENCE_PER_100K'");
        assertThat(region.get("REGION_LEVEL")).isEqualTo("SIDO");
        assertThat(region.get("REGION_NAME")).isEqualTo("부산");
        assertThat(((Number) region.get("PERIOD_WEEK")).intValue()).isZero();
        assertThat(((Date) region.get("PERIOD_START")).toLocalDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(((Date) region.get("PERIOD_END")).toLocalDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat((BigDecimal) region.get("METRIC_VALUE")).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("H2: 같은 실행을 다시 돌려도 행 수는 그대로(멱등 upsert)이고 적재 이력만 쌓인다")
    void rerunIsIdempotent() {
        NotifiableImportFacade facade = facade(100);
        facade.importNotifiable(new NotifiableImportCommand(CURRENT_YEAR, RUN_AT));
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class);

        facade.importNotifiable(new NotifiableImportCommand(CURRENT_YEAR, RUN_AT));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class)).isEqualTo(rows).isEqualTo(12);
        assertThat(snapshotCount("IMPORTED")).isEqualTo(2 * PLANNED);
    }

    /** 스텝 트랜잭션(무자원)을 흉내 내 바깥을 감싼다 — 앞 요청의 쓰기가 커넥션을 스레드에 묶어 두면 다음 원천 호출 내내 쥔다. */
    @Test
    @DisplayName("H2: 스텝 트랜잭션 안에서도 모든 원천 호출은 DataSource 커넥션이 스레드에 묶이지 않은 채 일어난다")
    void sourceIsCalledOutsideTransaction() {
        List<Boolean> bound = new ArrayList<>();
        willAnswer(invocation -> {
            bound.add(TransactionSynchronizationManager.hasResource(dataSource));
            consume(invocation, 1);
            return weeklyFetch(invocation.getArgument(0));
        }).given(sourcePort).fetchWeekly(anyInt(), any());
        willAnswer(invocation -> {
            bound.add(TransactionSynchronizationManager.hasResource(dataSource));
            consume(invocation, 3);
            return regionFetch(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).given(sourcePort).fetchRegion(anyInt(), any(), anyString(), any());

        NotifiableImportResult result = new TransactionTemplate(new ResourcelessTransactionManager())
            .execute(status -> facade(100).importNotifiable(new NotifiableImportCommand(CURRENT_YEAR, RUN_AT)));

        assertThat(result.requests()).isEqualTo(PLANNED);
        assertThat(bound).hasSize(PLANNED).containsOnly(false);
    }

    @Test
    @DisplayName("요청 하나의 KDCA_API_ERROR 는 그 요청만 본문 없는 FAILED 이력을 남기고 나머지는 반영한 뒤, 끝에 RUN_FAILED 로 실패한다")
    void isolatesRequestFailure() {
        String failingKey = "notifiable:region:per100k:2026:sido=02";
        willThrow(sourceFailure(NotifiableImportErrorCode.KDCA_API_ERROR))
            .given(sourcePort).fetchRegion(eq(2026), eq(NotifiableRegionMeasure.INCIDENCE_PER_100K), eq("02"), any());

        assertRunFailed(facade(100), exception -> assertThat(exception.getMessage()).contains(
            "planned=10", "imported=9", "failed=1", "notAttempted=0", "aborted=false", "abortedBy=-", "failedRequestKeys=[" + failingKey + "]"));

        assertThat(snapshotCount("IMPORTED")).isEqualTo(PLANNED - 1);
        Map<String, Object> failed = snapshot(failingKey);
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("ERROR_CODE")).isEqualTo("NOTIFIABLE_IMPORT_004");
        assertThat(failed.get("CONTENT_SHA256")).isNull();
        assertThat(((Number) failed.get("BYTE_LENGTH")).intValue()).isZero();
        assertThat(((Number) failed.get("ROW_COUNT")).intValue()).isZero();
        assertThat(((Number) failed.get("IMPORTED_COUNT")).intValue()).isZero();
        assertThat(((Timestamp) failed.get("RUN_STARTED_AT")).toLocalDateTime()).isEqualTo(RUN_AT);
        // 이후 요청(2025)도 그대로 불렀다.
        verify(sourcePort, times(8)).fetchRegion(anyInt(), any(), anyString(), any());
    }

    @Test
    @DisplayName("시각 출처는 하나다 — 성공 · 실패 이력의 created_at 과 모든 행의 synced_at 이 실행 시작 때 정한 같은 시각이다")
    void usesSingleSyncedAt() {
        willThrow(sourceFailure(NotifiableImportErrorCode.KDCA_HTTP_ERROR)).given(sourcePort).fetchWeekly(eq(2025), any());

        assertRunFailed(facade(100), exception -> assertThat(exception.getMessage()).contains("failed=1"));

        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT created_at FROM official_source_snapshot", Timestamp.class))
            .containsExactly(Timestamp.valueOf(SYNCED_AT));
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT synced_at FROM official_surveillance", Timestamp.class))
            .containsExactly(Timestamp.valueOf(SYNCED_AT));
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT run_started_at FROM official_source_snapshot", Timestamp.class))
            .containsExactly(Timestamp.valueOf(RUN_AT));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = NotifiableImportErrorCode.class,
        names = {"KDCA_CREDENTIALS_MISSING", "KDCA_SERVICE_KEY_LOOKS_ENCODED", "KDCA_GATEWAY_REJECTED", "REQUEST_BUDGET_EXCEEDED"})
    @DisplayName("키 · 게이트웨이 · 호출 상한 오류는 그 요청에 FAILED 이력을 남기고 남은 요청을 부르지 않는다 — RUN_FAILED 에 중단 표시")
    void abortsRunOnKeyOrBudgetError(NotifiableImportErrorCode errorCode) {
        willThrow(sourceFailure(errorCode))
            .given(sourcePort).fetchRegion(eq(2026), eq(NotifiableRegionMeasure.CASE_COUNT), eq("01"), any());

        assertRunFailed(facade(100), exception -> {
            assertThat(exception.getMessage()).contains("planned=10", "imported=2", "failed=1", "notAttempted=7", "aborted=true",
                "abortedBy=" + errorCode.getCode(), "failedRequestKeys=[" + THIRD_REQUEST_KEY + "]");
            assertThat(exception.getCause()).isInstanceOfSatisfying(NotifiableImportException.class,
                cause -> assertThat(cause.getErrorCode()).isEqualTo(errorCode));
        });

        verify(sourcePort, times(2)).fetchWeekly(anyInt(), any());
        verify(sourcePort, times(1)).fetchRegion(anyInt(), any(), anyString(), any());
        assertThat(snapshotCount("IMPORTED")).isEqualTo(2);
        assertThat(snapshotCount("FAILED")).isEqualTo(1);
        assertThat(snapshot(THIRD_REQUEST_KEY).get("ERROR_CODE")).isEqualTo(errorCode.getCode());
    }

    @Test
    @DisplayName("실제 예산: 실행당 호출 상한 3 이면 네 번째 요청이 호출 전에 막혀 FAILED(NOTIFIABLE_IMPORT_010)로 남고 실행이 멈춘다")
    void budgetStopsRunBeforeCall() {
        assertRunFailed(facade(3), exception -> assertThat(exception.getMessage()).contains(
            "imported=3", "failed=1", "notAttempted=6", "aborted=true", "abortedBy=NOTIFIABLE_IMPORT_010"));

        verify(sourcePort, times(2)).fetchRegion(anyInt(), any(), anyString(), any());
        assertThat(snapshot("notifiable:region:count:2026:sido=02").get("ERROR_CODE")).isEqualTo("NOTIFIABLE_IMPORT_010");
        assertThat(snapshotCount("IMPORTED")).isEqualTo(3);
    }

    @Test
    @DisplayName("쓰기 실패(DataAccessException)는 프로세서가 FAILED(OFFICIAL_INGEST_003)를 남기고, 실행은 멈춘다 — DB 문제면 이후 쓰기도 실패한다")
    void abortsRunOnWriteFailure() {
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        NotifiableImportFacade facade = facade(100, new FailingSurveillancePort(dbDown), snapshotAdapter);

        assertRunFailed(facade, exception -> {
            assertThat(exception.getMessage()).contains("imported=0", "failed=1", "notAttempted=9", "aborted=true", "abortedBy=OFFICIAL_INGEST_003");
            assertThat(exception.getCause()).isSameAs(dbDown);
        });

        verify(sourcePort, times(1)).fetchWeekly(anyInt(), any());
        verify(sourcePort, never()).fetchRegion(anyInt(), any(), anyString(), any());
        assertThat(snapshot("notifiable:periodBasic:week:2026").get("ERROR_CODE")).isEqualTo("OFFICIAL_INGEST_003");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance", Integer.class)).isZero();
    }

    @Test
    @DisplayName("원천 실패의 FAILED 이력마저 못 쓰면(DB 장애) 실행을 멈춘다")
    void abortsRunWhenFailureCannotBeRecorded() {
        willThrow(sourceFailure(NotifiableImportErrorCode.KDCA_CALL_FAILED)).given(sourcePort).fetchWeekly(eq(2026), any());
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        NotifiableImportFacade facade = facade(100, surveillanceAdapter, snapshot -> {
            throw dbDown;
        });

        assertRunFailed(facade, exception -> {
            assertThat(exception.getMessage()).contains("failed=1", "notAttempted=9", "aborted=true", "abortedBy=OFFICIAL_INGEST_003");
            assertThat(exception.getCause()).isSameAs(dbDown);
            assertThat(dbDown.getSuppressed()).singleElement().isInstanceOf(NotifiableImportException.class);
        });
        verify(sourcePort, times(1)).fetchWeekly(anyInt(), any());
    }

    @Test
    @DisplayName("검증 실패(같은 자연키 두 번)는 그 요청만 FAILED(OFFICIAL_INGEST_002)이고 나머지는 계속 반영한다")
    void continuesAfterValidationFailure() {
        NotifiableWeeklyRow row = new NotifiableWeeklyRow(2026, 1, "에볼라바이러스병", "에볼라바이러스병", "제1급", BigDecimal.ZERO);
        willAnswer(invocation -> {
            consume(invocation, 1);
            return fetch(List.of(row, row), 0);
        }).given(sourcePort).fetchWeekly(eq(2026), any());

        assertRunFailed(facade(100), exception -> assertThat(exception.getMessage()).contains(
            "imported=9", "failed=1", "notAttempted=0", "aborted=false", "failedRequestKeys=[notifiable:periodBasic:week:2026]"));

        assertThat(snapshot("notifiable:periodBasic:week:2026").get("ERROR_CODE")).isEqualTo("OFFICIAL_INGEST_002");
        assertThat(snapshotCount("IMPORTED")).isEqualTo(PLANNED - 1);
    }

    @Test
    @DisplayName("검증 실패의 FAILED 이력마저 못 쓰면(프로세서가 suppressed 로 붙인다) DB 문제로 보고 실행을 멈춘다")
    void abortsRunWhenValidationFailureCannotBeRecorded() {
        NotifiableWeeklyRow row = new NotifiableWeeklyRow(2026, 1, "에볼라바이러스병", "에볼라바이러스병", "제1급", BigDecimal.ZERO);
        willAnswer(invocation -> {
            consume(invocation, 1);
            return fetch(List.of(row, row), 0);
        }).given(sourcePort).fetchWeekly(eq(2026), any());
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        NotifiableImportFacade facade = facade(100, surveillanceAdapter, snapshot -> {
            throw dbDown;
        });

        assertRunFailed(facade, exception -> {
            assertThat(exception.getMessage()).contains("imported=0", "failed=1", "notAttempted=9", "aborted=true", "abortedBy=OFFICIAL_INGEST_003",
                "failedRequestKeys=[notifiable:periodBasic:week:2026]");
            assertThat(exception.getCause()).isInstanceOfSatisfying(OfficialIngestException.class, cause -> {
                assertThat(cause.getErrorCode()).isEqualTo(OfficialIngestErrorCode.DUPLICATE_NATURAL_KEY);
                assertThat(cause.getSuppressed()).containsExactly(dbDown);
            });
        });
        verify(sourcePort, times(1)).fetchWeekly(anyInt(), any());
        verify(sourcePort, never()).fetchRegion(anyInt(), any(), anyString(), any());
    }

    @Test
    @DisplayName("변환 실패(52주인 2025년의 53주)는 그 요청만 FAILED(KDCA_RESPONSE_INVALID)이고 나머지는 계속 반영한다")
    void treatsConversionFailureAsResponseInvalid() {
        willAnswer(invocation -> {
            consume(invocation, 1);
            return fetch(List.of(new NotifiableWeeklyRow(2025, 53, "엠폭스", "@엠폭스", "제2급", BigDecimal.ONE)), 0);
        }).given(sourcePort).fetchWeekly(eq(2025), any());

        assertRunFailed(facade(100), exception -> assertThat(exception.getMessage()).contains(
            "imported=9", "failed=1", "aborted=false", "failedRequestKeys=[notifiable:periodBasic:week:2025]"));

        assertThat(snapshot("notifiable:periodBasic:week:2025").get("ERROR_CODE")).isEqualTo("NOTIFIABLE_IMPORT_007");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_surveillance WHERE period_year = 2025 AND period_type = 'WEEK'",
            Integer.class)).isZero();
    }

    @Test
    @DisplayName("currentYear 를 지정하면(백필) 그 해와 전년을 받는다 — 실행 연도는 부르지 않는다")
    void plansFromCommandYear() {
        NotifiableImportResult result = facade(100).importNotifiable(new NotifiableImportCommand(2025, RUN_AT));

        assertThat(result.currentYear()).isEqualTo(2025);
        verify(sourcePort).fetchWeekly(eq(2025), any());
        verify(sourcePort).fetchWeekly(eq(2024), any());
        verify(sourcePort, times(4)).fetchRegion(eq(2025), any(), anyString(), any());
        verify(sourcePort, times(4)).fetchRegion(eq(2024), any(), anyString(), any());
        verify(sourcePort, never()).fetchWeekly(eq(2026), any());
        assertThat(snapshot("notifiable:periodBasic:week:2024").get("STATUS")).isEqualTo("IMPORTED");
    }

    @Test
    @DisplayName("실패 request_key 는 메시지에 앞에서부터 20개까지만 싣는다")
    void limitsFailedKeysInMessage() {
        willThrow(sourceFailure(NotifiableImportErrorCode.KDCA_API_ERROR)).given(sourcePort).fetchWeekly(anyInt(), any());
        willThrow(sourceFailure(NotifiableImportErrorCode.KDCA_API_ERROR)).given(sourcePort).fetchRegion(anyInt(), any(), anyString(), any());
        List<String> sidoCodes = List.of("01", "02", "03", "04", "05", "06");
        NotifiableImportFacade facade = new NotifiableImportFacade(sourcePort, new NotifiableRecordProcessor(),
            processor(surveillanceAdapter, snapshotAdapter), new NotifiableImportProperties(sidoCodes, 100), CLOCK);

        assertThatThrownBy(() -> facade.importNotifiable(new NotifiableImportCommand(CURRENT_YEAR, RUN_AT)))
            .isInstanceOfSatisfying(NotifiableImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(NotifiableImportErrorCode.RUN_FAILED);
                // 2 + 4 × 6 = 26건 실패. 20번째는 2025 발생 수 시도 06, 21번째(2025 10만 명당 01)부터 빠진다.
                assertThat(exception.getMessage()).contains("failed=26", "notifiable:region:count:2025:sido=06")
                    .doesNotContain("notifiable:region:per100k:2025:sido=01");
            });
        assertThat(snapshotCount("FAILED")).isEqualTo(26);
    }

    private NotifiableImportFacade facade(int maxCallsPerRun) {
        return facade(maxCallsPerRun, surveillanceAdapter, snapshotAdapter);
    }

    private NotifiableImportFacade facade(int maxCallsPerRun, OfficialSurveillanceBulkPort surveillancePort, OfficialSourceSnapshotBulkPort snapshotPort) {
        return new NotifiableImportFacade(sourcePort, new NotifiableRecordProcessor(), processor(surveillancePort, snapshotPort),
            new NotifiableImportProperties(SIDO_CODES, maxCallsPerRun), CLOCK);
    }

    private OfficialIngestProcessor processor(OfficialSurveillanceBulkPort surveillancePort, OfficialSourceSnapshotBulkPort snapshotPort) {
        return new OfficialIngestProcessor(surveillancePort, snapshotPort, snowflakeIdGenerator, new DataSourceTransactionManager(dataSource));
    }

    private static void assertRunFailed(NotifiableImportFacade facade, Consumer<NotifiableImportException> requirements) {
        assertThatThrownBy(() -> facade.importNotifiable(new NotifiableImportCommand(CURRENT_YEAR, RUN_AT)))
            .isInstanceOfSatisfying(NotifiableImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(NotifiableImportErrorCode.RUN_FAILED);
                assertThat(exception.getMessage()).startsWith("[NOTIFIABLE_IMPORT_020]");
                requirements.accept(exception);
            });
    }

    /** 메시지 자리 표시자가 코드마다 0 ~ 4개라 넉넉히 넘긴다 (남는 인자는 무시된다). */
    private static NotifiableImportException sourceFailure(NotifiableImportErrorCode errorCode) {
        return new NotifiableImportException(errorCode, "op", "x", "x", "x");
    }

    private static void consume(InvocationOnMock invocation, int budgetIndex) {
        invocation.<KdcaCallBudget>getArgument(budgetIndex).consume();
    }

    /** 2주 2개 감염병 — 38주 엠폭스는 값이 비었다. 받은 원천 행은 계 행을 더해 3행. */
    private static NotifiableFetch<NotifiableWeeklyRow> weeklyFetch(int year) {
        return fetch(List.of(
            new NotifiableWeeklyRow(year, 1, "에볼라바이러스병", "에볼라바이러스병", "제1급", BigDecimal.ZERO),
            new NotifiableWeeklyRow(year, 38, "엠폭스", "@엠폭스", "제2급", null)), 1);
    }

    private static NotifiableFetch<NotifiableRegionRow> regionFetch(int year, NotifiableRegionMeasure measure, String sidoCode) {
        String value = measure == NotifiableRegionMeasure.CASE_COUNT ? "1041" : "0.25";
        return fetch(List.of(new NotifiableRegionRow(year, sidoCode, SIDO_NAMES.get(sidoCode), "에볼라바이러스병", "에볼라바이러스병", "제1급",
            new BigDecimal(value))), 0);
    }

    private static <T> NotifiableFetch<T> fetch(List<T> rows, int nullValueCount) {
        return new NotifiableFetch<>(rows, SHA256, 2048, rows.size() + 1, nullValueCount, 1);
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
        public int upsertAll(List<IdentifiedOfficialRecord> rows, long sourceSnapshotId,
            LocalDateTime syncedAt) {
            throw failure;
        }
    }
}
