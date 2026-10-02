package com.sneezecast.domainlayer.report.application.service.processor;

import com.sneezecast.domainlayer.report.application.command.ReportSubmitCommand;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.application.port.out.ReportDistrictQueryPort;
import com.sneezecast.domainlayer.report.application.port.out.WeeklyReportRepositoryPort;
import com.sneezecast.domainlayer.report.application.port.out.query.ReportDistrictQueryResult;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import com.sneezecast.domainlayer.report.domain.model.WeeklyReport;
import com.sneezecast.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이번 주 보고 제출 · 취소. 메서드 하나가 트랜잭션 하나다 — 동시 제출 재시도가 트랜잭션 밖(WebFacade)에서 새 트랜잭션으로 다시 불러야 해서
 * Facade 가 아니라 여기에 건다 ({@code WeeklyReportRepositoryPort} 동시 제출 계약).
 */
@Component
@RequiredArgsConstructor
public class ReportCommandProcessor {

    private final WeeklyReportRepositoryPort weeklyReportRepositoryPort;
    private final ReportDistrictQueryPort reportDistrictQueryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 행정동이 현행인지 확인하고, 이번 주 보고가 없으면 새로 저장하고 있으면 고친다 (같은 주는 한 행 — 집계에서 한 번만 센다).
     *
     * <p><b>{@code REPORT_001} 을 여기서 잡지 않는다.</b> insert 가 unique 에서 지면 이 트랜잭션은 rollback-only 라 같은 트랜잭션에서 다시 시도할 수
     * 없다 — 예외를 그대로 내보내 롤백시키고, 재시도는 호출자가 새 트랜잭션으로 한다. 고치려던 행이 그 사이 취소돼 없어진 경우도 같은 코드로 내보낸다
     * (다시 부르면 첫 보고로 저장된다).
     *
     * @throws ReportException 없는 행정동 {@code REPORT_002} · 폐지 행정동 {@code REPORT_003}(400), 동시 처리에서 짐 {@code REPORT_001}(409)
     */
    @Transactional
    public ReportInfo submit(String reporterKey, ReportWeek isoWeek, ReportSubmitCommand command) {
        requireActiveDistrict(command.districtCode());

        Optional<WeeklyReport> current = weeklyReportRepositoryPort.findByReporterKeyAndIsoWeek(reporterKey, isoWeek);
        if (current.isEmpty()) {
            WeeklyReport newReport = WeeklyReport.newReport(
                snowflakeIdGenerator.generateId(), reporterKey, isoWeek, command.districtCode(), command.symptomGroups());
            return ReportInfo.from(weeklyReportRepositoryPort.insert(newReport));
        }
        return weeklyReportRepositoryPort.updateCurrent(reporterKey, isoWeek, command.districtCode(), command.symptomGroups())
            .map(ReportInfo::from)
            .orElseThrow(() -> new ReportException(ReportErrorCode.CONCURRENT_SUBMISSION));
    }

    /** 이번 주 보고를 지운다. 지울 행이 없어도 성공이다 (멱등 취소). */
    @Transactional
    public void cancel(String reporterKey, ReportWeek isoWeek) {
        weeklyReportRepositoryPort.deleteByReporterKeyAndIsoWeek(reporterKey, isoWeek);
    }

    private void requireActiveDistrict(String districtCode) {
        ReportDistrictQueryResult district = reportDistrictQueryPort.findByCode(districtCode)
            .orElseThrow(() -> new ReportException(ReportErrorCode.DISTRICT_NOT_FOUND));
        if (!district.active()) {
            throw new ReportException(ReportErrorCode.DISTRICT_RETIRED);
        }
    }
}
