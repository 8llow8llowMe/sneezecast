package com.sneezecast.domainlayer.districtimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sneezecast.domainlayer.districtimport.adapter.out.persistence.JdbcDistrictBulkAdapter;
import com.sneezecast.domainlayer.districtimport.application.command.DistrictImportCommand;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportErrorCode;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportException;
import com.sneezecast.domainlayer.districtimport.application.model.DistrictImportResult;
import com.sneezecast.domainlayer.districtimport.application.port.out.DistrictBulkPort;
import com.sneezecast.domainlayer.districtimport.application.port.out.DistrictSourcePort;
import com.sneezecast.domainlayer.districtimport.application.service.processor.DistrictRetireGuardProcessor;
import com.sneezecast.domainlayer.districtimport.domain.model.DistrictSnapshot;
import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import com.sneezecast.global.properties.DistrictImportProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class DistrictImportFacadeTest {

    private static final int YEAR = 2025;

    private DistrictSourcePort sourcePort;
    private DistrictBulkPort bulkPort;
    private DistrictImportFacade facade;

    @BeforeEach
    void setUp() {
        sourcePort = mock(DistrictSourcePort.class);
        bulkPort = mock(DistrictBulkPort.class);
        DistrictRetireGuardProcessor guard = new DistrictRetireGuardProcessor(new DistrictImportProperties(0.02));
        facade = new DistrictImportFacade(sourcePort, bulkPort, guard, new ResourcelessTransactionManager());

        given(bulkPort.findLastLoadedYear()).willReturn(OptionalInt.of(2024));
        given(bulkPort.upsertAll(anyList(), anyInt(), any())).willAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        given(bulkPort.retire(anyCollection(), anyInt(), any())).willAnswer(invocation -> ((Collection<?>) invocation.getArgument(0)).size());
    }

    @Test
    @DisplayName("정상 흐름: 연도 검사 → 원천 → 현행 조회 → upsert → 사라진 코드를 직전 연도로 폐지")
    void importsAndRetiresInOrder() {
        // 2024 의 녹산동(21120560)이 2025 에 녹산동 · 신호동(21120561 · 21120562)으로 분동됐다 (data-api-analysis §1-2).
        List<ImportedDistrict> districts = new ArrayList<>(manyDistricts("11", 100));
        districts.add(district("21120561", "녹산동"));
        districts.add(district("21120562", "신호동"));
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11", "21"), districts));
        Set<String> active = codes(manyDistricts("11", 100));
        active.add("21120560");
        given(bulkPort.findActiveCodes()).willReturn(active);

        DistrictImportResult result = facade.importDistricts(new DistrictImportCommand(YEAR, false));

        assertThat(result).isEqualTo(new DistrictImportResult(YEAR, 102, 102, 1));
        InOrder order = inOrder(bulkPort, sourcePort);
        order.verify(bulkPort).findLastLoadedYear();
        order.verify(sourcePort).fetchSnapshot(YEAR);
        order.verify(bulkPort).findActiveCodes();
        order.verify(bulkPort).upsertAll(eq(districts), eq(YEAR), any(LocalDateTime.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> retired = ArgumentCaptor.forClass(Collection.class);
        order.verify(bulkPort).retire(retired.capture(), eq(YEAR - 1), any(LocalDateTime.class));
        assertThat(retired.getValue()).containsExactly("21120560");
    }

    @Test
    @DisplayName("첫 적재(현행 0건)는 폐지 비율 0 으로 통과한다")
    void firstImportPasses() {
        given(bulkPort.findLastLoadedYear()).willReturn(OptionalInt.empty());
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11"), manyDistricts("11", 3)));
        given(bulkPort.findActiveCodes()).willReturn(Set.of());

        DistrictImportResult result = facade.importDistricts(new DistrictImportCommand(YEAR, false));

        assertThat(result).isEqualTo(new DistrictImportResult(YEAR, 3, 3, 0));
    }

    @Test
    @DisplayName("폐지 비율이 임계값(2%)을 넘으면 아무것도 쓰지 않고 MASS_RETIRE_BLOCKED 로 실패한다 — 메시지에 건수 · 비율")
    void blocksMassRetireWithoutWriting() {
        // 현행 100 중 3개가 사라진다 = 3%.
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11"), manyDistricts("11", 97)));
        given(bulkPort.findActiveCodes()).willReturn(codes(manyDistricts("11", 100)));

        assertThatThrownBy(() -> facade.importDistricts(new DistrictImportCommand(YEAR, false)))
            .isInstanceOfSatisfying(DistrictImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(DistrictImportErrorCode.MASS_RETIRE_BLOCKED);
                assertThat(exception.getMessage()).contains("retiring=3", "active=100", "ratio=0.0300", "maxRatio=0.0200");
            });
        verify(bulkPort, never()).upsertAll(anyList(), anyInt(), any());
        verify(bulkPort, never()).retire(anyCollection(), anyInt(), any());
    }

    @Test
    @DisplayName("임계값과 같은 비율(2%)은 막지 않는다 — '넘으면' 막는다")
    void ratioEqualToThresholdPasses() {
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11"), manyDistricts("11", 98)));
        given(bulkPort.findActiveCodes()).willReturn(codes(manyDistricts("11", 100)));

        DistrictImportResult result = facade.importDistricts(new DistrictImportCommand(YEAR, false));

        assertThat(result.retired()).isEqualTo(2);
    }

    @Test
    @DisplayName("allowMassRetire=true 면 임계값을 넘어도 upsert · 폐지를 진행한다")
    void allowMassRetireProceeds() {
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11"), manyDistricts("11", 50)));
        given(bulkPort.findActiveCodes()).willReturn(codes(manyDistricts("11", 100)));

        DistrictImportResult result = facade.importDistricts(new DistrictImportCommand(YEAR, true));

        assertThat(result).isEqualTo(new DistrictImportResult(YEAR, 50, 50, 50));
    }

    @Test
    @DisplayName("이미 적재된 연도보다 과거면 원천을 부르기 전에 YEAR_REGRESSION 으로 실패한다")
    void rejectsYearRegression() {
        given(bulkPort.findLastLoadedYear()).willReturn(OptionalInt.of(2025));

        assertThatThrownBy(() -> facade.importDistricts(new DistrictImportCommand(2024, false)))
            .isInstanceOfSatisfying(DistrictImportException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(DistrictImportErrorCode.YEAR_REGRESSION));
        verifyNoInteractions(sourcePort);
        verify(bulkPort, never()).upsertAll(anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("같은 연도 재실행은 허용한다 (멱등)")
    void sameYearRerunIsAllowed() {
        given(bulkPort.findLastLoadedYear()).willReturn(OptionalInt.of(YEAR));
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11"), manyDistricts("11", 3)));
        given(bulkPort.findActiveCodes()).willReturn(codes(manyDistricts("11", 3)));

        assertThat(facade.importDistricts(new DistrictImportCommand(YEAR, false))).isEqualTo(new DistrictImportResult(YEAR, 3, 3, 0));
    }

    @Test
    @DisplayName("폐지만 있던 해(2026, 신규 코드 없음)가 마지막 적재 연도면 2025 로 되돌려 돌릴 수 없다")
    void rejectsYearBeforeRetireOnlyYear() {
        given(bulkPort.findLastLoadedYear()).willReturn(OptionalInt.of(2026));

        assertThatThrownBy(() -> facade.importDistricts(new DistrictImportCommand(2025, false)))
            .isInstanceOfSatisfying(DistrictImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(DistrictImportErrorCode.YEAR_REGRESSION);
                assertThat(exception.getMessage()).contains("year=2025", "lastLoadedYear=2026");
            });
        verifyNoInteractions(sourcePort);
    }

    /**
     * 실제 JDBC 어댑터(H2 MySQL 모드)와 primary 매니저로 연도 흐름을 끝까지 돌린다 — 마지막 적재 연도 계산이 폐지만 있던 해를 놓치면,
     * 과거 연도 재적재가 통과해 그 해에 폐지된 동을 현행으로 되살린다.
     */
    @Test
    @DisplayName("H2: 빈 테이블 첫 적재 허용 → 폐지만 있는 2026 적재 → 2026 재실행 허용 → 2025 재적재는 YEAR_REGRESSION, 폐지된 동은 그대로")
    void yearFlowAgainstRealTable() {
        DriverManagerDataSource dataSource = h2DataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DistrictImportFacade realFacade = realFacade(dataSource, new JdbcDistrictBulkAdapter(jdbcTemplate));

        List<ImportedDistrict> districts2025 = manyDistricts("11", 100);
        // 2026: 신규 코드 없이 1개(1%)만 사라진다.
        List<ImportedDistrict> districts2026 = districts2025.subList(1, 100);
        given(sourcePort.fetchSnapshot(2025)).willReturn(new DistrictSnapshot(2025, List.of("11"), districts2025));
        given(sourcePort.fetchSnapshot(2026)).willReturn(new DistrictSnapshot(2026, List.of("11"), districts2026));

        assertThat(realFacade.importDistricts(new DistrictImportCommand(2025, false))).isEqualTo(new DistrictImportResult(2025, 100, 100, 0));
        assertThat(realFacade.importDistricts(new DistrictImportCommand(2026, false))).isEqualTo(new DistrictImportResult(2026, 99, 99, 1));
        assertThat(realFacade.importDistricts(new DistrictImportCommand(2026, false))).isEqualTo(new DistrictImportResult(2026, 99, 99, 0));

        assertThatThrownBy(() -> realFacade.importDistricts(new DistrictImportCommand(2025, false)))
            .isInstanceOfSatisfying(DistrictImportException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(DistrictImportErrorCode.YEAR_REGRESSION));
        Integer validToYear = jdbcTemplate.queryForObject("SELECT valid_to_year FROM district WHERE code = ?", Integer.class, districts2025.get(0).code());
        assertThat(validToYear).isEqualTo(2025);
    }

    @Test
    @DisplayName("H2: 2025 적재 → 2026 X 폐지 → 2027 X 재등장(valid_to_year 가 지워진다) → 2026 재실행은 YEAR_REGRESSION")
    void reappearanceDoesNotHideLastLoadedYear() {
        DriverManagerDataSource dataSource = h2DataSource();
        DistrictImportFacade realFacade = realFacade(dataSource, new JdbcDistrictBulkAdapter(new JdbcTemplate(dataSource)));
        List<ImportedDistrict> all = manyDistricts("11", 100);
        List<ImportedDistrict> withoutX = all.subList(1, 100);
        given(sourcePort.fetchSnapshot(2025)).willReturn(new DistrictSnapshot(2025, List.of("11"), all));
        given(sourcePort.fetchSnapshot(2026)).willReturn(new DistrictSnapshot(2026, List.of("11"), withoutX));
        given(sourcePort.fetchSnapshot(2027)).willReturn(new DistrictSnapshot(2027, List.of("11"), all));

        realFacade.importDistricts(new DistrictImportCommand(2025, false));
        assertThat(realFacade.importDistricts(new DistrictImportCommand(2026, false)).retired()).isEqualTo(1);
        realFacade.importDistricts(new DistrictImportCommand(2027, false));

        assertThatThrownBy(() -> realFacade.importDistricts(new DistrictImportCommand(2026, false)))
            .isInstanceOfSatisfying(DistrictImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(DistrictImportErrorCode.YEAR_REGRESSION);
                assertThat(exception.getMessage()).contains("lastLoadedYear=2027");
            });
    }

    /**
     * 스텝 트랜잭션({@code taskletTransactionManager} = 무자원)을 흉내 내 바깥을 감싼다. 무자원 매니저는 트랜잭션 동기화를 켜므로, 원천 호출 전
     * 읽기가 자체 트랜잭션 없이 {@code JdbcTemplate} 을 쓰면 커넥션이 스레드에 묶인 채 원천 호출을 기다린다.
     */
    @Test
    @DisplayName("H2: 스텝 트랜잭션 안에서도 원천 호출 동안 DataSource 커넥션이 스레드에 묶여 있지 않다")
    void sourceIsCalledWithoutBoundConnection() {
        DriverManagerDataSource dataSource = h2DataSource();
        DistrictImportFacade realFacade = realFacade(dataSource, new JdbcDistrictBulkAdapter(new JdbcTemplate(dataSource)));
        List<ImportedDistrict> districts = manyDistricts("11", 10);
        given(sourcePort.fetchSnapshot(2025)).willReturn(new DistrictSnapshot(2025, List.of("11"), districts));
        realFacade.importDistricts(new DistrictImportCommand(2025, false));
        boolean[] connectionBound = {true};
        given(sourcePort.fetchSnapshot(2026)).willAnswer(invocation -> {
            connectionBound[0] = TransactionSynchronizationManager.hasResource(dataSource);
            return new DistrictSnapshot(2026, List.of("11"), districts);
        });

        DistrictImportResult result = new TransactionTemplate(new ResourcelessTransactionManager())
            .execute(status -> realFacade.importDistricts(new DistrictImportCommand(2026, false)));

        assertThat(connectionBound[0]).isFalse();
        assertThat(result).isEqualTo(new DistrictImportResult(2026, 10, 10, 0));
    }

    @Test
    @DisplayName("H2: 폐지가 실패하면 같은 트랜잭션의 upsert 도 롤백된다 — 이름 · last_seen_year 가 그대로다")
    void retireFailureRollsBackUpsert() {
        DriverManagerDataSource dataSource = h2DataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcDistrictBulkAdapter jdbcAdapter = new JdbcDistrictBulkAdapter(jdbcTemplate);
        List<ImportedDistrict> districts2025 = manyDistricts("11", 100);
        given(sourcePort.fetchSnapshot(2025)).willReturn(new DistrictSnapshot(2025, List.of("11"), districts2025));
        realFacade(dataSource, jdbcAdapter).importDistricts(new DistrictImportCommand(2025, false));

        List<ImportedDistrict> renamed2026 = districts2025.subList(1, 100).stream()
            .map(district -> new ImportedDistrict(district.code(), "새" + district.name(), district.sidoCode(), district.sidoName(),
                district.sigunguCode(), district.sigunguName()))
            .toList();
        given(sourcePort.fetchSnapshot(2026)).willReturn(new DistrictSnapshot(2026, List.of("11"), renamed2026));
        DistrictImportFacade failingRetireFacade = realFacade(dataSource, new FailingRetireBulkPort(jdbcAdapter));

        assertThatThrownBy(() -> failingRetireFacade.importDistricts(new DistrictImportCommand(2026, false)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM district WHERE name LIKE '새%'", Integer.class)).isZero();
        assertThat(jdbcAdapter.findLastLoadedYear()).hasValue(2025);
        assertThat(jdbcAdapter.findActiveCodes()).hasSize(100);
    }

    private static DriverManagerDataSource h2DataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:district-facade-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("districtimport/district-schema.sql")).execute(dataSource);
        return dataSource;
    }

    private DistrictImportFacade realFacade(DriverManagerDataSource dataSource, DistrictBulkPort port) {
        return new DistrictImportFacade(sourcePort, port, new DistrictRetireGuardProcessor(new DistrictImportProperties(0.02)),
            new DataSourceTransactionManager(dataSource));
    }

    /** upsert 까지는 실제로 쓰고 폐지에서 실패한다. */
    private record FailingRetireBulkPort(DistrictBulkPort delegate) implements DistrictBulkPort {

        @Override
        public int upsertAll(List<ImportedDistrict> districts, int year, LocalDateTime syncedAt) {
            return delegate.upsertAll(districts, year, syncedAt);
        }

        @Override
        public int retire(Collection<String> codes, int validToYear, LocalDateTime updatedAt) {
            throw new IllegalStateException("retire failed");
        }

        @Override
        public Set<String> findActiveCodes() {
            return delegate.findActiveCodes();
        }

        @Override
        public OptionalInt findLastLoadedYear() {
            return delegate.findLastLoadedYear();
        }
    }

    @Test
    @DisplayName("빈 스냅샷은 SNAPSHOT_EMPTY 로 실패하고 현행 조회 · 쓰기를 하지 않는다")
    void rejectsEmptySnapshot() {
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11"), List.of()));

        assertImportFailsWithoutWriting(DistrictImportErrorCode.SNAPSHOT_EMPTY);
    }

    @Test
    @DisplayName("읍면동이 하나도 없는 시도가 있으면(잘린 응답) SNAPSHOT_SIDO_MISSING 으로 실패한다")
    void rejectsSnapshotMissingSido() {
        given(sourcePort.fetchSnapshot(YEAR)).willReturn(new DistrictSnapshot(YEAR, List.of("11", "21", "26"), manyDistricts("11", 3)));

        DistrictImportException exception = assertImportFailsWithoutWriting(DistrictImportErrorCode.SNAPSHOT_SIDO_MISSING);
        assertThat(exception.getMessage()).contains("[21, 26]");
    }

    @Test
    @DisplayName("같은 코드가 두 번 나오면 SNAPSHOT_DUPLICATE_CODE 로 실패한다")
    void rejectsDuplicateCode() {
        given(sourcePort.fetchSnapshot(YEAR))
            .willReturn(new DistrictSnapshot(YEAR, List.of("11"), List.of(district("11240660", "가락1동"), district("11240660", "가락1동"))));

        DistrictImportException exception = assertImportFailsWithoutWriting(DistrictImportErrorCode.SNAPSHOT_DUPLICATE_CODE);
        assertThat(exception.getMessage()).contains("code=11240660");
    }

    private DistrictImportException assertImportFailsWithoutWriting(DistrictImportErrorCode expected) {
        DistrictImportException[] thrown = new DistrictImportException[1];
        assertThatThrownBy(() -> facade.importDistricts(new DistrictImportCommand(YEAR, false)))
            .isInstanceOfSatisfying(DistrictImportException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(expected);
                thrown[0] = exception;
            });
        verify(bulkPort, never()).findActiveCodes();
        verify(bulkPort, never()).upsertAll(anyList(), anyInt(), any());
        verify(bulkPort, never()).retire(anyCollection(), anyInt(), any());
        return thrown[0];
    }

    private static List<ImportedDistrict> manyDistricts(String sidoCode, int count) {
        List<ImportedDistrict> districts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            districts.add(district(sidoCode + "%06d".formatted(index), "동" + index));
        }
        return districts;
    }

    private static ImportedDistrict district(String code, String name) {
        return new ImportedDistrict(code, name, code.substring(0, 2), "시도", code.substring(0, 5), "시군구");
    }

    private static Set<String> codes(List<ImportedDistrict> districts) {
        Set<String> codes = new HashSet<>();
        districts.forEach(district -> codes.add(district.code()));
        return codes;
    }
}
