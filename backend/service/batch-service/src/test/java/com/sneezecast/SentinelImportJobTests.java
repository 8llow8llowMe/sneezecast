package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import com.sneezecast.domainlayer.official.domain.enums.OfficialAgeGroup;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportErrorCode;
import com.sneezecast.domainlayer.sentinelimport.application.exception.SentinelImportException;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelCallBudget;
import com.sneezecast.domainlayer.sentinelimport.application.port.out.SentinelSourcePort;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelFetch;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelIliRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelPathogenRow;
import com.sneezecast.domainlayer.sentinelimport.domain.model.SentinelRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code sentinelImportJob} 배선 게이트 — 검증기 · 무자원 스텝 · tasklet · Facade · 실제 쓰기 경로가 한 컨텍스트에서 이어지는지 본다.
 *
 * <p>{@code NotifiableImportJobTests} 와 같은 대체값에 원천 포트만 가짜로 바꾼다 — 실제 감염병포털은 부르지 않는다. 스케줄러 이름 · H2 DB 는
 * 다른 컨텍스트와 겹치지 않게 따로 둔다. 컨텍스트 안의 DB 를 테스트끼리 나눠 쓰므로 행 수는 {@code run_started_at} 으로 거른다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "BATCH_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "BATCH_DB_URL=jdbc:h2:mem:sentinel-import-job;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "BATCH_DB_USERNAME=sa",
    "BATCH_DB_PASSWORD=",
    "spring.batch.jdbc.initialize-schema=always",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:official/official-schema.sql",
    "BATCH_SCHEDULE_ENABLED=false",
    "spring.quartz.properties.org.quartz.scheduler.instanceName=sentinel-import-job-scheduler"
})
class SentinelImportJobTests {

    private static final String SHA256 = "e".repeat(64);

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("sentinelImportJob")
    private Job sentinelImportJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SentinelSourcePort sentinelSourcePort;

    @BeforeEach
    void setUp() {
        willAnswer(invocation -> {
            consume(invocation.getArgument(1));
            SentinelRequest request = invocation.getArgument(0);
            SentinelPathogenRow row = new SentinelPathogenRow(request.to().year(), request.to().week(), "TOTAL", "계", "계", BigDecimal.TEN);
            return new SentinelFetch<>(List.of(row), SHA256, 100, 1, 2, 0, 0);
        }).given(sentinelSourcePort).fetchPathogens(any(), any());
        willAnswer(invocation -> {
            consume(invocation.getArgument(1));
            SentinelRequest request = invocation.getArgument(0);
            SentinelIliRow row = new SentinelIliRow(request.seasonStartYear(), 36, OfficialAgeGroup.AGE_0, BigDecimal.ONE);
            return new SentinelFetch<>(List.of(row), SHA256, 100, 7, 2, 0, 0);
        }).given(sentinelSourcePort).fetchInfluenza(any(), any());
    }

    @Test
    @DisplayName("runAt 으로 실행하면 COMPLETED 이고, 계획 4건(2026-10-09 는 41주)의 IMPORTED 적재 이력이 run_started_at = runAt 으로 남는다")
    void completesWithImportedSnapshots() throws Exception {
        JobExecution execution = launch(new JobParametersBuilder().addString("runAt", "2026-10-09T06:00:00"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotCount("2026-10-09T06:00:00", "IMPORTED")).isEqualTo(4);
        assertThat(snapshotCount("2026-10-09T06:00:00", "FAILED")).isZero();
    }

    @Test
    @DisplayName("요청 하나가 형식 변경으로 실패하면 나머지는 반영되고 Job 은 FAILED 다 — EXIT_MESSAGE 에 RUN_FAILED 코드가 남는다")
    void failsJobWhenRequestFails() throws Exception {
        willThrow(new SentinelImportException(SentinelImportErrorCode.SCHEMA_CHANGED, "influ(2026-2027)", "captionList mismatch"))
            .given(sentinelSourcePort).fetchInfluenza(eq(SentinelRequest.season(2026)), any());

        JobExecution execution = launch(new JobParametersBuilder().addString("runAt", "2026-10-16T06:00:00"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).contains("SENTINEL_IMPORT_020");
        assertThat(snapshotCount("2026-10-16T06:00:00", "IMPORTED")).isEqualTo(3);
        assertThat(snapshotCount("2026-10-16T06:00:00", "FAILED")).isEqualTo(1);
    }

    @Test
    @DisplayName("baseDate 를 주면 그 날을 기준으로 계획한다 — 2026-06-05 는 3건(지난 절기 없음)이다")
    void plansFromBaseDateParameter() throws Exception {
        JobExecution execution = launch(new JobParametersBuilder().addString("runAt", "2026-10-23T06:00:00").addString("baseDate", "2026-06-05"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotCount("2026-10-23T06:00:00", "IMPORTED")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE request_key = 'sentinel:ari:2026-16~2026-23'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 baseDate 는 JobInstance 를 만들기 전에 거절된다")
    void rejectsInvalidBaseDateBeforeLaunch() {
        assertThatThrownBy(() -> launch(new JobParametersBuilder().addString("runAt", "2026-10-30T06:00:00").addString("baseDate", "2026-10-31")))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("'baseDate'");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION_PARAMS WHERE PARAMETER_VALUE = '2026-10-30T06:00:00'",
            Integer.class)).isZero();
    }

    private JobExecution launch(JobParametersBuilder parameters) throws Exception {
        return jobLauncher.run(sentinelImportJob, parameters.toJobParameters());
    }

    private static void consume(SentinelCallBudget budget) {
        budget.consume();
        budget.consume();
    }

    private int snapshotCount(String runAt, String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE run_started_at = ? AND status = ?", Integer.class,
            Timestamp.valueOf(LocalDateTime.parse(runAt)), status);
    }
}
