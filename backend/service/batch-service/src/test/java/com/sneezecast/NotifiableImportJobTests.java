package com.sneezecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportErrorCode;
import com.sneezecast.domainlayer.notifiableimport.application.exception.NotifiableImportException;
import com.sneezecast.domainlayer.notifiableimport.application.model.KdcaCallBudget;
import com.sneezecast.domainlayer.notifiableimport.application.port.out.NotifiableSourcePort;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableFetch;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;
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
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code notifiableImportJob} 배선 게이트 — 검증기 · 무자원 스텝 · tasklet · Facade · 실제 쓰기 경로가 한 컨텍스트에서 이어지는지 본다.
 *
 * <p>{@code BatchServiceApplicationTests} 와 같은 대체값(키 없음 · 스케줄 끔)에 적재 테이블({@code official/official-schema.sql})을 더하고,
 * 원천 포트만 가짜로 바꾼다 — 실제 질병관리청 API 는 부르지 않는다. 시도는 2개(계획 10건)다. 스케줄러 이름 · H2 DB 는 다른 컨텍스트와 겹치지
 * 않게 따로 둔다. 컨텍스트 안의 DB 를 테스트끼리 나눠 쓰므로 행 수는 {@code run_started_at} 으로 거른다.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=dev",
    "eureka.client.enabled=false",
    "BATCH_SERVICE_PORT=0",
    "SERVICE_DISCOVERY_HOSTNAME=localhost",
    "SERVICE_DISCOVERY_PORT=8761",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "BATCH_DB_URL=jdbc:h2:mem:notifiable-import-job;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "BATCH_DB_USERNAME=sa",
    "BATCH_DB_PASSWORD=",
    "spring.batch.jdbc.initialize-schema=always",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:official/official-schema.sql",
    "BATCH_SCHEDULE_ENABLED=false",
    "notifiable-import.sido-codes=01,02",
    "spring.quartz.properties.org.quartz.scheduler.instanceName=notifiable-import-job-scheduler"
})
class NotifiableImportJobTests {

    private static final int PLANNED = 10;
    private static final String SHA256 = "c".repeat(64);

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("notifiableImportJob")
    private Job notifiableImportJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private NotifiableSourcePort notifiableSourcePort;

    @BeforeEach
    void setUp() {
        willAnswer(invocation -> {
            invocation.<KdcaCallBudget>getArgument(1).consume();
            NotifiableWeeklyRow row = new NotifiableWeeklyRow(invocation.getArgument(0), 38, "엠폭스", "@엠폭스", "제2급", BigDecimal.ONE);
            return new NotifiableFetch<>(List.of(row), SHA256, 100, 2, 0, 1);
        }).given(notifiableSourcePort).fetchWeekly(anyInt(), any());
        willAnswer(invocation -> {
            invocation.<KdcaCallBudget>getArgument(3).consume();
            NotifiableRegionRow row = new NotifiableRegionRow(invocation.getArgument(0), invocation.getArgument(2), "시도", "엠폭스", "@엠폭스", "제2급",
                BigDecimal.TEN);
            return new NotifiableFetch<>(List.of(row), SHA256, 100, 2, 0, 1);
        }).given(notifiableSourcePort).fetchRegion(anyInt(), any(), anyString(), any());
    }

    @Test
    @DisplayName("runAt 으로 실행하면 COMPLETED 이고, 계획 10건의 IMPORTED 적재 이력이 run_started_at = runAt 으로 남는다")
    void completesWithImportedSnapshots() throws Exception {
        JobExecution execution = launch("2026-10-06T05:00:00");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotCount("2026-10-06T05:00:00", "IMPORTED")).isEqualTo(PLANNED);
        assertThat(snapshotCount("2026-10-06T05:00:00", "FAILED")).isZero();
    }

    @Test
    @DisplayName("요청 하나가 실패하면 나머지는 반영되고 Job 은 FAILED 다 — EXIT_MESSAGE 에 RUN_FAILED 코드가 남는다")
    void failsJobWhenRequestFails() throws Exception {
        willThrow(new NotifiableImportException(NotifiableImportErrorCode.KDCA_HTTP_ERROR, "Region", 502))
            .given(notifiableSourcePort).fetchRegion(eq(2025), eq(NotifiableRegionMeasure.CASE_COUNT), eq("01"), any());

        JobExecution execution = launch("2026-10-13T05:00:00");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).contains("NOTIFIABLE_IMPORT_020");
        assertThat(snapshotCount("2026-10-13T05:00:00", "IMPORTED")).isEqualTo(PLANNED - 1);
        assertThat(snapshotCount("2026-10-13T05:00:00", "FAILED")).isEqualTo(1);
    }

    private JobExecution launch(String runAt) throws Exception {
        return jobLauncher.run(notifiableImportJob, new JobParametersBuilder().addString("runAt", runAt).toJobParameters());
    }

    private int snapshotCount(String runAt, String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM official_source_snapshot WHERE run_started_at = ? AND status = ?", Integer.class,
            Timestamp.valueOf(LocalDateTime.parse(runAt)), status);
    }
}
