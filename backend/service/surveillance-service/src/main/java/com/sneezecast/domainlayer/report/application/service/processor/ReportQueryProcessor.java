package com.sneezecast.domainlayer.report.application.service.processor;

import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본인 보고 조회 (이번 주 · 지난 보고 내역). 제출 · 취소와 같이 트랜잭션은 Processor 메서드에 건다 ({@link ReportCommandProcessor} 참고).
 */
@Component
@RequiredArgsConstructor
public class ReportQueryProcessor {

    private final WeeklyReportRepositoryPort weeklyReportRepositoryPort;

    /** 이번 주 보고가 없으면 빈 값이다 — 부모(회원 · 주)당 0~1개인 하위 리소스라 오류가 아니다. */
    @Transactional(readOnly = true)
    public Optional<ReportInfo> findCurrent(String reporterKey, ReportWeek isoWeek) {
        return weeklyReportRepositoryPort.findByReporterKeyAndIsoWeek(reporterKey, isoWeek).map(ReportInfo::from);
    }

    /**
     * 이번 주를 뺀 지난 보고, 최근 주부터. 창은 지난 주부터 보관 기간({@link WeeklyReport#RETENTION_WEEKS}, 이번 주 포함)의 가장 오래된 주까지라
     * 많아야 {@code RETENTION_WEEKS - 1} 건이다. 이번 주 보고는 {@link #findCurrent} 가 정본이라 여기 넣지 않는다. 이번 주는 호출자가
     * {@code /current} 와 같은 기준({@code ReportWeekCalculator}, KST)으로 정해 넘긴다.
     */
    @Transactional(readOnly = true)
    public List<ReportInfo> findPast(String reporterKey, ReportWeek currentWeek) {
        ReportWeek oldest = currentWeek.minusWeeks(WeeklyReport.RETENTION_WEEKS - 1);
        return weeklyReportRepositoryPort.findAllByReporterKeyAndIsoWeekBetween(reporterKey, oldest, currentWeek.previous()).stream()
            .map(ReportInfo::from)
            .toList();
    }
}
