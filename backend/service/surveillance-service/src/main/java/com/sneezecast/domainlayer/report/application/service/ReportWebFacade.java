package com.sneezecast.domainlayer.report.application.service;

import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.application.command.ReportSubmitCommand;
import com.sneezecast.domainlayer.report.application.exception.ReportErrorCode;
import com.sneezecast.domainlayer.report.application.exception.ReportException;
import com.sneezecast.domainlayer.report.application.info.ReportInfo;
import com.sneezecast.domainlayer.report.application.port.in.ReportWebUseCase;
import com.sneezecast.domainlayer.report.application.service.presenter.ReportPresenter;
import com.sneezecast.domainlayer.report.application.service.processor.ReportCommandProcessor;
import com.sneezecast.domainlayer.report.application.service.processor.ReportQueryProcessor;
import com.sneezecast.domainlayer.report.domain.model.ReportWeek;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 본인 이번 주 보고 오케스트레이션. <b>이 Facade 에는 트랜잭션을 걸지 않는다</b> — 같은 주 첫 보고가 동시에 들어와 진 쪽은 그 트랜잭션이
 * rollback-only 라, 재시도는 트랜잭션 밖인 여기서 새 트랜잭션으로 해야 한다. 트랜잭션은 Processor 메서드마다 하나씩 건다 (architecture-guide §3-1).
 *
 * <p>회원 ID 는 가명 키를 만드는 데만 쓰고 아래 계층으로 넘기지 않는다. 회원 ID · 가명 키 · 증상은 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportWebFacade implements ReportWebUseCase {

    private final ReporterKeyGenerator reporterKeyGenerator;
    private final ReportWeekCalculator reportWeekCalculator;
    private final ReportCommandProcessor reportCommandProcessor;
    private final ReportQueryProcessor reportQueryProcessor;
    private final ReportPresenter reportPresenter;

    /**
     * {@code REPORT_001}(동시 처리에서 짐)만 잡아 <b>새 트랜잭션으로 정확히 한 번</b> 다시 부른다. 이긴 쪽 행이 있으니 이번에는 수정 경로를 탄다
     * (고치려던 행이 그 사이 취소됐으면 첫 보고로 저장된다). 두 번째도 지면 그대로 409 다. 다른 오류(행정동 · 무결성 위반)는 다시 부르지 않는다.
     *
     * <p>보고 주는 첫 시도에서 한 번만 정한다 — 재시도가 주 경계(월요일 0시 KST)를 넘어도 같은 주에 쓴다.
     */
    @Override
    public WeeklyReportResponse submitCurrent(long memberId, ReportSubmitCommand command) {
        String reporterKey = reporterKeyGenerator.reporterKey(memberId);
        ReportWeek isoWeek = reportWeekCalculator.currentWeek();
        ReportInfo saved;
        try {
            saved = reportCommandProcessor.submit(reporterKey, isoWeek, command);
        } catch (ReportException exception) {
            if (exception.getErrorCode() != ReportErrorCode.CONCURRENT_SUBMISSION) {
                throw exception;
            }
            log.info("weekly report concurrent submission, retrying once isoWeek={}", isoWeek.value());
            saved = reportCommandProcessor.submit(reporterKey, isoWeek, command);
        }
        return reportPresenter.toResponse(saved);
    }

    @Override
    public WeeklyReportResponse getCurrent(long memberId) {
        return reportQueryProcessor.findCurrent(reporterKeyGenerator.reporterKey(memberId), reportWeekCalculator.currentWeek())
            .map(reportPresenter::toResponse)
            .orElse(null);
    }

    @Override
    public void cancelCurrent(long memberId) {
        reportCommandProcessor.cancel(reporterKeyGenerator.reporterKey(memberId), reportWeekCalculator.currentWeek());
    }
}
