package com.sneezecast.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.member.application.model.ReportPurgeCallResult;
import com.sneezecast.domainlayer.member.application.model.ReportPurgeRunResult;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeCommandPort;
import com.sneezecast.domainlayer.member.application.port.out.ReportPurgeRequestRepositoryPort;
import com.sneezecast.domainlayer.member.application.service.processor.ReportPurgeExecutionProcessor;
import com.sneezecast.domainlayer.member.domain.enums.PurgeReason;
import com.sneezecast.domainlayer.member.domain.model.ReportPurgeRequest;
import com.sneezecast.global.properties.ReportPurgeProperties;
import com.sneezecast.security.auth.jwt.JwtAuthProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 파기 회차 흐름을 본다 — 파사드와 실제 Processor 에 저장소 · 호출 포트만 mock 으로 끼운다. 로그에 회원 ID 가 남지 않는지 함께 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportPurgeFacadeTest {

    private static final String ACCESS_KEY = "sneezecast-report-purge-test-access-secret-key-0123456789-0123456789";
    private static final String REFRESH_KEY = "sneezecast-report-purge-test-refresh-secret-key-0123456789-0123456789";
    private static final Duration SETTLE_WINDOW = Duration.ofMinutes(20);
    private static final int ALERT_THRESHOLD = 3;

    /** 다른 숫자와 겹치지 않는 긴 고유 회원 ID. 로그에 나오면 안 된다. */
    private static final long MEMBER_A = 7350912846153L;
    private static final long MEMBER_B = 7350912846271L;
    private static final long MEMBER_C = 7350912846389L;

    private ReportPurgeRequestRepositoryPort repositoryPort;
    private ReportPurgeCommandPort commandPort;
    private ReportPurgeFacade facade;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(ReportPurgeRequestRepositoryPort.class);
        commandPort = mock(ReportPurgeCommandPort.class);
        ReportPurgeProperties properties = new ReportPurgeProperties("false", Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(5), 50,
            ALERT_THRESHOLD, Duration.ofDays(365), "0 30 4 * * *");
        JwtAuthProperties jwtAuthProperties = new JwtAuthProperties(ACCESS_KEY, Duration.ofMinutes(15), REFRESH_KEY, Duration.ofDays(14));
        facade = new ReportPurgeFacade(new ReportPurgeExecutionProcessor(repositoryPort), commandPort, properties, jwtAuthProperties);
    }

    @Test
    @DisplayName("대상을 access 수명 + 여유 기준으로 고르고, 1차 성공은 미완료로 · 대기가 지난 성공은 완료로 남긴다")
    void runsDueRequests(CapturedOutput output) {
        LocalDateTime before = LocalDateTime.now();
        ReportPurgeRequest fresh = request(11L, MEMBER_A, LocalDateTime.now());
        ReportPurgeRequest settled = request(12L, MEMBER_B, LocalDateTime.now().minusHours(1));
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(fresh, settled));
        when(commandPort.purgeReporter(anyLong())).thenReturn(ReportPurgeCallResult.purged());
        when(repositoryPort.recordSuccess(anyLong(), any(), any())).thenReturn(1);

        ReportPurgeRunResult result = facade.purgeDue();

        assertThat(result).isEqualTo(new ReportPurgeRunResult(2, 1, 1, 0, false));
        ArgumentCaptor<LocalDateTime> dueAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repositoryPort).findDue(dueAt.capture(), eq(50));
        assertThat(dueAt.getValue()).isBetween(before.minus(SETTLE_WINDOW), LocalDateTime.now().minus(SETTLE_WINDOW));
        verify(repositoryPort).recordSuccess(eq(11L), notNull(), isNull());
        verify(repositoryPort).recordSuccess(eq(12L), notNull(), notNull());
        assertThat(output.getAll()).contains("report purge run finished due=2 completed=1 purged=1 failed=0 halted=false");
        assertNoMemberIdIn(output);
    }

    @Test
    @DisplayName("서킷이 열리면 그 항목을 기록하지 않고 회차를 멈춘다 — 남은 항목은 부르지 않는다")
    void haltsWhenCircuitOpens(CapturedOutput output) {
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(
            request(21L, MEMBER_A, LocalDateTime.now()), request(22L, MEMBER_B, LocalDateTime.now()), request(23L, MEMBER_C, LocalDateTime.now())));
        when(commandPort.purgeReporter(MEMBER_A)).thenReturn(ReportPurgeCallResult.purged());
        when(commandPort.purgeReporter(MEMBER_B)).thenReturn(ReportPurgeCallResult.circuitOpen());
        when(repositoryPort.recordSuccess(anyLong(), any(), any())).thenReturn(1);

        ReportPurgeRunResult result = facade.purgeDue();

        assertThat(result).isEqualTo(new ReportPurgeRunResult(3, 0, 1, 0, true));
        verify(commandPort, never()).purgeReporter(MEMBER_C);
        verify(repositoryPort, never()).recordFailure(anyLong(), any(), any());
        verify(repositoryPort, never()).recordSuccess(eq(22L), any(), any());
        assertThat(output.getAll()).contains("report purge run halted, circuit open requestId=22");
        assertNoMemberIdIn(output);
    }

    @Test
    @DisplayName("한 항목의 예상 밖 예외는 다음 항목을 막지 않는다 — 로그에는 예외 클래스 이름만 남는다")
    void isolatesUnexpectedItemFailure(CapturedOutput output) {
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(request(31L, MEMBER_A, LocalDateTime.now()), request(32L, MEMBER_B, LocalDateTime.now())));
        when(commandPort.purgeReporter(MEMBER_A)).thenThrow(new IllegalStateException("DELETE /internal/v1/reporters/" + MEMBER_A + " exploded"));
        when(commandPort.purgeReporter(MEMBER_B)).thenReturn(ReportPurgeCallResult.purged());
        when(repositoryPort.recordSuccess(anyLong(), any(), any())).thenReturn(1);

        ReportPurgeRunResult result = facade.purgeDue();

        assertThat(result).isEqualTo(new ReportPurgeRunResult(2, 0, 1, 1, false));
        verify(repositoryPort).recordSuccess(eq(32L), any(), any());
        assertThat(output.getAll()).contains("report purge item failed unexpectedly requestId=31 exception=IllegalStateException")
            .doesNotContain("exploded");
        assertNoMemberIdIn(output);
    }

    @Test
    @DisplayName("실패는 요약과 함께 기록하고, 기록 뒤 시도 횟수가 임계값 미만이면 WARN 이다")
    void recordsFailureBelowThreshold(CapturedOutput output) {
        ReportPurgeRequest failing = request(41L, MEMBER_A, LocalDateTime.now());
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(failing));
        when(commandPort.purgeReporter(MEMBER_A)).thenReturn(ReportPurgeCallResult.unavailable("status=503"));
        when(repositoryPort.recordFailure(eq(41L), anyString(), any())).thenReturn(1);
        when(repositoryPort.findById(41L)).thenReturn(Optional.of(withAttempts(failing, ALERT_THRESHOLD - 1)));

        ReportPurgeRunResult result = facade.purgeDue();

        assertThat(result).isEqualTo(new ReportPurgeRunResult(1, 0, 0, 1, false));
        verify(repositoryPort).recordFailure(eq(41L), eq("UNAVAILABLE status=503"), notNull());
        assertThat(output.getAll()).contains("report purge call failed requestId=41 attemptCount=2 error=UNAVAILABLE status=503")
            .doesNotContain("alert threshold");
        assertNoMemberIdIn(output);
    }

    @Test
    @DisplayName("기록 뒤 시도 횟수가 임계값에 닿으면 ERROR 경보를 남긴다 — 요청 ID · 시도 횟수 · 요약만")
    void alertsAtThreshold(CapturedOutput output) {
        ReportPurgeRequest failing = request(51L, MEMBER_A, LocalDateTime.now());
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(failing));
        when(commandPort.purgeReporter(MEMBER_A)).thenReturn(ReportPurgeCallResult.rejected(404, null));
        when(repositoryPort.recordFailure(eq(51L), anyString(), any())).thenReturn(1);
        when(repositoryPort.findById(51L)).thenReturn(Optional.of(withAttempts(failing, ALERT_THRESHOLD)));

        facade.purgeDue();

        assertThat(output.getAll())
            .contains("ERROR")
            .contains("report purge attempts reached alert threshold requestId=51 attemptCount=3 threshold=3 error=REJECTED status=404 resultCode=null");
        assertNoMemberIdIn(output);
    }

    @Test
    @DisplayName("그 사이 다른 실행이 완료한 행은 실패로 세지 않는다")
    void skipsWhenAlreadyCompletedElsewhere() {
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(request(61L, MEMBER_A, LocalDateTime.now())));
        when(commandPort.purgeReporter(MEMBER_A)).thenReturn(ReportPurgeCallResult.unavailable("status=502"));
        when(repositoryPort.recordFailure(eq(61L), anyString(), any())).thenReturn(0);

        assertThat(facade.purgeDue()).isEqualTo(new ReportPurgeRunResult(1, 0, 0, 0, false));
        verify(repositoryPort, never()).findById(anyLong());
    }

    @Test
    @DisplayName("성공 기록이 0건(그 사이 다른 실행이 완료)이면 완료 · 1차 성공 어느 쪽으로도 세지 않는다")
    void skipsSuccessWhenAlreadyCompletedElsewhere() {
        when(repositoryPort.findDue(any(), anyInt())).thenReturn(List.of(request(71L, MEMBER_A, LocalDateTime.now().minusHours(1))));
        when(commandPort.purgeReporter(MEMBER_A)).thenReturn(ReportPurgeCallResult.purged());
        when(repositoryPort.recordSuccess(anyLong(), any(), any())).thenReturn(0);

        assertThat(facade.purgeDue()).isEqualTo(new ReportPurgeRunResult(1, 0, 0, 0, false));
    }

    @Test
    @DisplayName("정리는 보관 기간(365일) 전에 완료된 행을 지우고 건수만 남긴다")
    void cleansUpCompleted(CapturedOutput output) {
        LocalDateTime before = LocalDateTime.now();
        when(repositoryPort.deleteCompletedBefore(any())).thenReturn(7);

        assertThat(facade.cleanUpCompleted()).isEqualTo(7);

        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repositoryPort).deleteCompletedBefore(threshold.capture());
        assertThat(threshold.getValue()).isBetween(before.minusDays(365), LocalDateTime.now().minusDays(365));
        assertThat(output.getAll()).contains("report purge cleanup finished deleted=7");
    }

    private static ReportPurgeRequest request(long id, long memberId, LocalDateTime requestedAt) {
        return ReportPurgeRequest.builder().id(id).memberId(memberId).reason(PurgeReason.HEALTH_CONSENT_WITHDRAWN).requestedAt(requestedAt).build();
    }

    private static ReportPurgeRequest withAttempts(ReportPurgeRequest request, int attemptCount) {
        return ReportPurgeRequest.builder().id(request.id()).memberId(request.memberId()).reason(request.reason()).requestedAt(request.requestedAt())
            .attemptCount(attemptCount).build();
    }

    private static void assertNoMemberIdIn(CapturedOutput output) {
        assertThat(output.getAll())
            .doesNotContain(String.valueOf(MEMBER_A))
            .doesNotContain(String.valueOf(MEMBER_B))
            .doesNotContain(String.valueOf(MEMBER_C));
    }
}
