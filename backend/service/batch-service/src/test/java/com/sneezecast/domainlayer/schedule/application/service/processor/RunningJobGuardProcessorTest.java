package com.sneezecast.domainlayer.schedule.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sneezecast.domainlayer.schedule.application.port.out.BatchJobExecutionQueryPort;
import com.sneezecast.domainlayer.schedule.application.port.out.query.RunningJobExecutionQueryResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이 가드가 틀리는 방향은 둘 다 나쁘다 — 너무 느슨하면 같은 행을 두 흐름이 쓰고, 너무 빡빡하면 죽은 JVM 이 남긴 STARTED 행 하나에 스케줄이
 * 영원히 막힌다.
 */
class RunningJobGuardProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-05T20:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofHours(6);
    private static final List<String> JOB_NAMES = List.of("notifiableImportJob", "sentinelImportJob");

    private final BatchJobExecutionQueryPort batchJobExecutionQueryPort = mock(BatchJobExecutionQueryPort.class);
    private final RunningJobGuardProcessor processor = new RunningJobGuardProcessor(batchJobExecutionQueryPort);

    @Test
    @DisplayName("staleAfter 안에 시작한 실행이 있으면 그 잡 이름으로 막는다 — 수동 실행 JVM 의 실행도 같은 메타데이터라 같이 막힌다")
    void blocksWhenRecentExecutionIsStillRunning() {
        given(batchJobExecutionQueryPort.findRunning(JOB_NAMES)).willReturn(List.of(
            new RunningJobExecutionQueryResult("sentinelImportJob", 91L, NOW.minus(Duration.ofHours(2)))));

        assertThat(processor.findBlocking(JOB_NAMES, NOW, STALE_AFTER)).contains("sentinelImportJob");
    }

    @Test
    @DisplayName("staleAfter 를 넘긴 실행만 있으면 죽은 JVM 의 잔재로 보고 통과시킨다")
    void ignoresAbandonedExecution() {
        given(batchJobExecutionQueryPort.findRunning(JOB_NAMES)).willReturn(List.of(
            new RunningJobExecutionQueryResult("notifiableImportJob", 42L, NOW.minus(Duration.ofHours(30))),
            new RunningJobExecutionQueryResult("sentinelImportJob", 43L, NOW.minus(STALE_AFTER))));

        assertThat(processor.findBlocking(JOB_NAMES, NOW, STALE_AFTER)).isEmpty();
    }

    @Test
    @DisplayName("여러 실행이 막으면 첫 번째를 돌려주고, 앞에 방치된 실행이 섞여 있어도 최근 실행으로 막는다")
    void returnsFirstRecentBlocker() {
        given(batchJobExecutionQueryPort.findRunning(JOB_NAMES)).willReturn(List.of(
            new RunningJobExecutionQueryResult("notifiableImportJob", 42L, NOW.minus(Duration.ofHours(30))),
            new RunningJobExecutionQueryResult("sentinelImportJob", 91L, NOW.minus(Duration.ofMinutes(10))),
            new RunningJobExecutionQueryResult("notifiableImportJob", 92L, NOW.minus(Duration.ofMinutes(5)))));

        assertThat(processor.findBlocking(JOB_NAMES, NOW, STALE_AFTER)).contains("sentinelImportJob");
    }

    @Test
    @DisplayName("실행 중인 잡이 없으면 통과시킨다")
    void passesWhenNothingIsRunning() {
        given(batchJobExecutionQueryPort.findRunning(JOB_NAMES)).willReturn(List.of());

        assertThat(processor.findBlocking(JOB_NAMES, NOW, STALE_AFTER)).isEmpty();
    }

    @Test
    @DisplayName("볼 잡 목록이 비어 있으면 메타데이터를 조회하지 않는다")
    void skipsQueryForEmptyJobNames() {
        assertThat(processor.findBlocking(List.of(), NOW, STALE_AFTER)).isEmpty();
        assertThat(processor.findBlocking(null, NOW, STALE_AFTER)).isEmpty();
        verifyNoInteractions(batchJobExecutionQueryPort);
    }
}
