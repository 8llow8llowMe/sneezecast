package com.sneezecast.domainlayer.report.application.service;

import com.sneezecast.domainlayer.report.application.port.in.ReportInternalUseCase;
import com.sneezecast.domainlayer.report.application.service.processor.ReportCommandProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 서비스 간 보고 API 오케스트레이션. report 의 다른 Facade 와 같이 <b>여기에는 트랜잭션을 걸지 않고</b> Processor 메서드에 건다 — 키 계산은
 * DB 와 무관하고, 지운 건수 로그를 커밋이 끝난 뒤에 남기기 위해서다.
 *
 * <p>회원 ID 는 가명 키를 만드는 데만 쓰고 아래 계층으로 넘기지 않는다. 회원 ID · 가명 키는 로그에 남기지 않고 지운 건수만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportInternalFacade implements ReportInternalUseCase {

    private final ReporterKeyGenerator reporterKeyGenerator;
    private final ReportCommandProcessor reportCommandProcessor;

    @Override
    public void purgeReporter(long memberId) {
        int deleted = reportCommandProcessor.purgeAll(reporterKeyGenerator.reporterKey(memberId));
        log.info("report purge deleted rows={}", deleted);
    }
}
