package com.sneezecast.domainlayer.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sneezecast.common.dto.metadata.CodeNameDescriptionMetadata;
import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.application.command.ReportSubmitCommand;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
import com.sneezecast.domainlayer.report.application.port.in.ReportWebUseCase;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.application.service.processor.ReportCommandProcessor;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 동시 제출 재시도를 <b>실제 트랜잭션 프록시</b>로 본다. 경합은 저장 포트 spy 로 재현한다 — 이긴 쪽 행이 이미 있는데 조회가 "없음" 을 보게 해 insert 가
 * unique 에서 지게 하거나, 수정이 0건(그 사이 취소)을 보게 한다.
 *
 * <p>진 쪽 트랜잭션은 rollback-only 다. Processor 가 그 안에서 예외를 삼키고 계속하면 커밋에서 {@code UnexpectedRollbackException} 이 나므로,
 * 재시도가 성공하거나 {@code REPORT_001} 이 그대로 나오는 것으로 "새 트랜잭션에서 다시 불렀다" 를 확인한다.
 *
 * <p>spy 빈은 컨텍스트 구성을 바꾸므로 공용 H2 컨텍스트({@code SurveillanceH2TestSupport})와 DB 를 나눈다 — 같은 인메모리 DB 를 두 컨텍스트가
 * create-drop 하지 않게.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "SURVEILLANCE_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "SURVEILLANCE_DB_URL=jdbc:h2:mem:surveillance-report-retry;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "SURVEILLANCE_DB_USERNAME=sa",
    "SURVEILLANCE_DB_PASSWORD=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "JWT_ACCESS_KEY=sneezecast-surveillance-report-retry-test-access-key-0123456789-0123456789",
    "REPORTER_KEY_PEPPER=sneezecast-surveillance-report-retry-test-pepper-0123456789"
})
class ReportWebFacadeRetryTest {

    private static final long MEMBER_ID = 1234567890123L;
    private static final String YEOKSAM_1 = "11230510";
    private static final String GARAK_1 = "11240660";
    private static final String RETIRED = "21120560";

    @Autowired
    private ReportWebUseCase reportWebUseCase;

    @Autowired
    private ReporterKeyGenerator reporterKeyGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private WeeklyReportRepositoryPort weeklyReportRepositoryPortBean;

    @MockitoSpyBean
    private ReportCommandProcessor reportCommandProcessorBean;

    // 주입된 빈은 트랜잭션 프록시이고 spy 는 그 안쪽 대상이다. 프록시로 stub · verify 하면 트랜잭션 advice 가 먼저 돌아(MANDATORY 위반 · 빈 트랜잭션)
    // 실제 호출처럼 동작하므로, stub · verify 는 안쪽 spy 에 한다. 운영 코드의 호출은 여전히 프록시를 거친다.
    private WeeklyReportRepositoryPort weeklyReportRepositoryPort;
    private ReportCommandProcessor reportCommandProcessor;

    @BeforeEach
    void setUp() {
        weeklyReportRepositoryPort = AopTestUtils.getUltimateTargetObject(weeklyReportRepositoryPortBean);
        reportCommandProcessor = AopTestUtils.getUltimateTargetObject(reportCommandProcessorBean);
        jdbcTemplate.update("DELETE FROM weekly_report");
        jdbcTemplate.update("DELETE FROM district");
        insertDistrict(YEOKSAM_1, "역삼1동", null);
        insertDistrict(GARAK_1, "가락1동", null);
        insertDistrict(RETIRED, "녹산동", (short) 2024);
    }

    @Test
    @DisplayName("첫 insert 가 unique 에서 지면(REPORT_001) 새 트랜잭션으로 한 번 다시 불러 수정 경로로 저장한다 — 행 1개, 수정 횟수 1")
    void lostFirstInsertIsRetriedAsUpdate() {
        submit(YEOKSAM_1);
        clearInvocations(weeklyReportRepositoryPort, reportCommandProcessor);
        doReturn(Optional.empty()).doCallRealMethod().when(weeklyReportRepositoryPort).findByReporterKeyAndIsoWeek(any(), any());
        List<String> writeTransactions = recordWriteTransactions();

        WeeklyReportResponse response = submit(GARAK_1, SymptomGroup.RESPIRATORY);

        assertThat(response.districtCode()).isEqualTo(GARAK_1);
        assertThat(response.symptomGroups()).extracting(CodeNameDescriptionMetadata::code).containsExactly("RESPIRATORY");
        verify(reportCommandProcessor, times(2)).submit(any(), any(), any());
        verify(weeklyReportRepositoryPort, times(1)).insert(any());
        verify(weeklyReportRepositoryPort, times(1)).updateCurrent(any(), any(), any(), any());
        assertThat(writeTransactions).containsExactly("insert:write", "updateCurrent:write");
        assertThat(rowCount()).isEqualTo(1);
        assertThat(row()).containsEntry("DISTRICT_CODE", GARAK_1);
        assertThat(number(row(), "REVISION_COUNT")).isEqualTo(1);
        assertThat(number(row(), "SYMPTOM_MASK")).isEqualTo(1);
    }

    @Test
    @DisplayName("다시 불러도 지면 REPORT_001 이 그대로 나간다 — UnexpectedRollbackException 이 아니고, 이긴 쪽 행은 그대로다")
    void secondLossIsConflict() {
        submit(YEOKSAM_1);
        clearInvocations(weeklyReportRepositoryPort, reportCommandProcessor);
        doReturn(Optional.empty()).when(weeklyReportRepositoryPort).findByReporterKeyAndIsoWeek(any(), any());

        assertThatThrownBy(() -> submit(GARAK_1, SymptomGroup.ENTERIC))
            .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReportErrorCode.CONCURRENT_SUBMISSION));

        verify(reportCommandProcessor, times(2)).submit(any(), any(), any());
        verify(weeklyReportRepositoryPort, times(2)).insert(any());
        assertThat(rowCount()).isEqualTo(1);
        assertThat(row()).containsEntry("DISTRICT_CODE", YEOKSAM_1);
        assertThat(number(row(), "REVISION_COUNT")).isZero();
        assertThat(number(row(), "SYMPTOM_MASK")).isZero();
    }

    @Test
    @DisplayName("고치려던 행이 그 사이 취소돼 수정이 0건이면 REPORT_001 로 보고 한 번 다시 부른다")
    void updateOfCancelledRowIsRetried() {
        submit(YEOKSAM_1);
        clearInvocations(weeklyReportRepositoryPort, reportCommandProcessor);
        doReturn(Optional.empty()).doCallRealMethod().when(weeklyReportRepositoryPort).updateCurrent(any(), any(), any(), any());

        WeeklyReportResponse response = submit(GARAK_1);

        assertThat(response.districtCode()).isEqualTo(GARAK_1);
        verify(reportCommandProcessor, times(2)).submit(any(), any(), any());
        verify(weeklyReportRepositoryPort, times(2)).updateCurrent(any(), any(), any(), any());
        assertThat(number(row(), "REVISION_COUNT")).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도에서도 수정이 0건이면 409 REPORT_001 이다")
    void repeatedEmptyUpdateIsConflict() {
        submit(YEOKSAM_1);
        clearInvocations(weeklyReportRepositoryPort, reportCommandProcessor);
        doReturn(Optional.empty()).when(weeklyReportRepositoryPort).updateCurrent(any(), any(), any(), any());

        assertThatThrownBy(() -> submit(GARAK_1))
            .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReportErrorCode.CONCURRENT_SUBMISSION));
        verify(reportCommandProcessor, times(2)).submit(any(), any(), any());
    }

    @Test
    @DisplayName("행정동 오류는 다시 부르지 않는다 — 없는 코드 REPORT_002, 폐지 코드 REPORT_003, 저장 포트는 건드리지 않는다")
    void districtErrorsAreNotRetried() {
        assertThatThrownBy(() -> submit("99999999"))
            .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReportErrorCode.DISTRICT_NOT_FOUND));
        assertThatThrownBy(() -> submit(RETIRED))
            .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ReportErrorCode.DISTRICT_RETIRED));

        verify(reportCommandProcessor, times(2)).submit(any(), any(), any());
        verify(weeklyReportRepositoryPort, never()).findByReporterKeyAndIsoWeek(any(), any());
        verify(weeklyReportRepositoryPort, never()).insert(any());
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("첫 보고 insert 와 취소 delete 는 읽기 전용이 아닌 쓰기 트랜잭션 안에서 돈다")
    void writesRunInReadWriteTransaction() {
        List<String> writeTransactions = recordWriteTransactions();

        submit(YEOKSAM_1);
        reportWebUseCase.cancelCurrent(MEMBER_ID);
        reportWebUseCase.cancelCurrent(MEMBER_ID);

        assertThat(writeTransactions).containsExactly("insert:write", "deleteByReporterKeyAndIsoWeek:write", "deleteByReporterKeyAndIsoWeek:write");
        assertThat(rowCount()).isZero();
    }

    /** 쓰기 포트 호출마다 그 시점의 트랜잭션 상태를 남긴다 — 트랜잭션이 없거나 읽기 전용이면 {@code :none} · {@code :read-only}. */
    private List<String> recordWriteTransactions() {
        List<String> states = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            String state = !TransactionSynchronizationManager.isActualTransactionActive() ? "none"
                : TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "read-only" : "write";
            states.add(invocation.getMethod().getName() + ":" + state);
            return invocation.callRealMethod();
        }).when(weeklyReportRepositoryPort).insert(any());
        doAnswer(invocation -> {
            states.add(invocation.getMethod().getName() + ":" + (TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "read-only" : "write"));
            return invocation.callRealMethod();
        }).when(weeklyReportRepositoryPort).updateCurrent(any(), any(), any(), any());
        doAnswer(invocation -> {
            states.add(invocation.getMethod().getName() + ":" + (TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "read-only" : "write"));
            return invocation.callRealMethod();
        }).when(weeklyReportRepositoryPort).deleteByReporterKeyAndIsoWeek(any(), any());
        return states;
    }

    private WeeklyReportResponse submit(String districtCode, SymptomGroup... symptomGroups) {
        return reportWebUseCase.submitCurrent(MEMBER_ID, ReportSubmitCommand.of(districtCode, List.of(symptomGroups)));
    }

    private int rowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weekly_report", Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("SELECT * FROM weekly_report WHERE reporter_key = ?", reporterKeyGenerator.reporterKey(MEMBER_ID));
    }

    // H2 는 TINYINT · SMALLINT 를 드라이버 버전에 따라 Byte · Short · Integer 로 줄 수 있어 숫자로만 비교한다.
    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    /** 행정동 행은 batch 처럼 JDBC 로 넣는다 (surveillance 리포지토리에는 저장 메서드가 없다). */
    private void insertDistrict(String code, String name, Short validToYear) {
        jdbcTemplate.update("""
                INSERT INTO district (id, code, name, sido_code, sido_name, sigungu_code, sigungu_name,
                                      valid_from_year, valid_to_year, last_seen_year, synced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '테스트시', ?, '테스트구', 2024, ?, 2025, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            Long.parseLong(code), code, name, code.substring(0, 2), code.substring(0, 5), validToYear);
    }
}
