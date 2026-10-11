package com.sneezecast.domainlayer.aggregate.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.aggregate.application.model.AggregateWeekResult;
import com.sneezecast.domainlayer.aggregate.application.service.processor.AggregateCommandProcessor;
import com.sneezecast.domainlayer.aggregate.domain.model.AggregateRule.WeekState;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 언제 어느 주를 재계산 · 마감하는지 본다 (시계 → 주). 재계산 · 마감 자체는 {@code AggregateCommandProcessorTest} 가 실제 DB 로 본다.
 */
class AggregateJobFacadeTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-10-05(월) ~ 10-11(일) 이 W41, 10-12(월) 부터 W42 다.
    private static final ReportWeek W40 = ReportWeek.of(LocalDate.of(2026, 9, 28));
    private static final ReportWeek W41 = ReportWeek.of(LocalDate.of(2026, 10, 5));
    private static final ReportWeek W42 = ReportWeek.of(LocalDate.of(2026, 10, 12));

    private final AggregateCommandProcessor processor = mock(AggregateCommandProcessor.class);

    @Test
    @DisplayName("현재 주는 KST 기준이다 — 일요일 23:59 KST(UTC 로는 같은 날 14:59)는 그 주, 월요일 00:00 KST 는 다음 주다")
    void recalculatesCurrentKstWeek() {
        when(processor.recalculateInProgress(W41)).thenReturn(result(W41, WeekState.IN_PROGRESS));
        when(processor.recalculateInProgress(W42)).thenReturn(result(W42, WeekState.IN_PROGRESS));

        facadeAt(LocalDateTime.of(2026, 10, 11, 23, 59)).recalculateCurrentWeek();
        facadeAt(LocalDateTime.of(2026, 10, 12, 0, 0)).recalculateCurrentWeek();

        InOrder order = inOrder(processor);
        order.verify(processor).recalculateInProgress(W41);
        order.verify(processor).recalculateInProgress(W42);
    }

    @Test
    @DisplayName("마감 유예(9분) 안이면 지난 주를 아직 닫지 않고, 월요일 00:09 부터 지난 주까지 닫는다 — 00:10 cron 회차는 1분 여유로 지난 주를 닫는다")
    void closesLastWeekOnlyAfterGrace() {
        facadeAt(LocalDateTime.of(2026, 10, 12, 0, 8, 59)).closeOverdueWeeks();
        verify(processor).findWeeksToClose(W41);

        facadeAt(LocalDateTime.of(2026, 10, 12, 0, 9)).closeOverdueWeeks();
        verify(processor).findWeeksToClose(W42);

        // 기본 cron 시각(00:10)에는 이미 지난 주가 마감 대상이다.
        facadeAt(LocalDateTime.of(2026, 10, 12, 0, 10)).closeOverdueWeeks();
        verify(processor, times(2)).findWeeksToClose(W42);
    }

    @Test
    @DisplayName("고른 주를 오래된 순으로 하나씩 닫고 결과를 그 순서로 준다")
    void closesWeeksInOrder() {
        when(processor.findWeeksToClose(W42)).thenReturn(List.of(W40, W41));
        when(processor.close(W40)).thenReturn(result(W40, WeekState.CLOSED));
        when(processor.close(W41)).thenReturn(result(W41, WeekState.CLOSED));

        List<AggregateWeekResult> results = facadeAt(LocalDateTime.of(2026, 10, 12, 0, 10)).closeOverdueWeeks();

        assertThat(results).extracting(AggregateWeekResult::isoWeek).containsExactly(W40, W41);
        InOrder order = inOrder(processor);
        order.verify(processor).close(W40);
        order.verify(processor).close(W41);
    }

    @Test
    @DisplayName("한 주의 마감이 실패하면 예외를 내보내고 뒤 주는 닫지 않는다 — 뒤 주가 앞 주를 마감된 전주 · 기준선으로 봐야 한다")
    void stopsAtFirstFailure() {
        when(processor.findWeeksToClose(W42)).thenReturn(List.of(W40, W41));
        when(processor.close(W40)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> facadeAt(LocalDateTime.of(2026, 10, 12, 0, 10)).closeOverdueWeeks()).isInstanceOf(IllegalStateException.class);
        verify(processor, never()).close(W41);
    }

    private AggregateJobFacade facadeAt(LocalDateTime kst) {
        Clock clock = Clock.fixed(kst.atZone(KST).toInstant(), KST);
        AggregateJobProperties properties = new AggregateJobProperties("true", Duration.ofMinutes(1), Duration.ofMinutes(10), "0 10 0 * * MON",
            Duration.ofMinutes(9));
        return new AggregateJobFacade(processor, properties, clock);
    }

    private static AggregateWeekResult result(ReportWeek week, WeekState state) {
        return new AggregateWeekResult(week, state, 0, 0, 0, 0, false);
    }
}
