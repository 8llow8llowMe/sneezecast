package com.sneezecast.domainlayer.report.application.service.processor;

import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본인 이번 주 보고 조회. 제출 · 취소와 같이 트랜잭션은 Processor 메서드에 건다 ({@link ReportCommandProcessor} 참고).
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
}
