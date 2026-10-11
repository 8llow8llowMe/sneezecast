package com.sneezecast.domainlayer.report.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.domain.enums.SymptomGroup;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지난 보고 내역의 창(지난 주 ~ 보관 기간의 가장 오래된 주)을 고정한다. 실제 질의 · 정렬은 {@code WeeklyReportRepositoryAdapterTest} 가,
 * 보안 필터부터의 흐름은 {@code ReportHistoryApiIntegrationTest} 가 본다.
 */
class ReportQueryProcessorTest {

    private static final String KEY = "a".repeat(64);

    private WeeklyReportRepositoryPort port;
    private ReportQueryProcessor processor;

    @BeforeEach
    void setUp() {
        port = mock(WeeklyReportRepositoryPort.class);
        processor = new ReportQueryProcessor(port);
    }

    @Test
    @DisplayName("이번 주를 빼고 지난 주부터 52주 보관 창(이번 주 포함)의 가장 오래된 주까지 묻는다 — 많아야 51주")
    void pastWindowExcludesCurrentWeekAndStaysInRetention() {
        processor.findPast(KEY, ReportWeek.parse("2026-W40"));

        verify(port).findAllByReporterKeyAndIsoWeekBetween(KEY, ReportWeek.parse("2025-W41"), ReportWeek.parse("2026-W39"));
    }

    @Test
    @DisplayName("ISO 연도 첫 주면 지난 주는 전년 마지막 주(53주가 있는 해는 W53)다")
    void pastWindowAcrossWeekBasedYear() {
        processor.findPast(KEY, ReportWeek.parse("2027-W01"));

        verify(port).findAllByReporterKeyAndIsoWeekBetween(KEY, ReportWeek.parse("2026-W03"), ReportWeek.parse("2026-W53"));
    }

    @Test
    @DisplayName("포트가 준 순서(최근 주부터)를 그대로 Info 로 옮긴다 — 보고 ID · 가명 키는 Info 에 없다")
    void keepsPortOrder() {
        ReportWeek w39 = ReportWeek.parse("2026-W39");
        ReportWeek w37 = ReportWeek.parse("2026-W37");
        when(port.findAllByReporterKeyAndIsoWeekBetween(KEY, ReportWeek.parse("2025-W41"), w39))
            .thenReturn(List.of(report(1L, w39, Set.of(SymptomGroup.RESPIRATORY)), report(2L, w37, Set.of())));

        List<ReportInfo> past = processor.findPast(KEY, ReportWeek.parse("2026-W40"));

        assertThat(past).extracting(ReportInfo::isoWeek).containsExactly(w39, w37);
        assertThat(past.get(0).symptomGroups()).containsExactly(SymptomGroup.RESPIRATORY);
        assertThat(past.get(1).symptomGroups()).isEmpty();
    }

    private static WeeklyReport report(long id, ReportWeek isoWeek, Set<SymptomGroup> symptoms) {
        LocalDateTime at = LocalDateTime.of(2026, 9, 1, 9, 0);
        return new WeeklyReport(id, KEY, isoWeek, "11230510", symptoms, 0, at, at);
    }
}
