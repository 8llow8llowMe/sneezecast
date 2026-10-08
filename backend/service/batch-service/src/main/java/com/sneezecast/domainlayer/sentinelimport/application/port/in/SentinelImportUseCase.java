package com.sneezecast.domainlayer.sentinelimport.application.port.in;

import com.sneezecast.domainlayer.sentinelimport.application.command.SentinelImportCommand;
import com.sneezecast.domainlayer.sentinelimport.application.model.SentinelImportResult;

public interface SentinelImportUseCase {

    /**
     * 기준일까지 최근 N주의 급성호흡기 · 장관감염증 병원체별 신고 수와 그 절기의 인플루엔자 의사환자 분율을 받아 공식 감시 자료로 적재한다.
     * 요청마다 적재 이력을 남기고, 실패한 요청이 하나라도 있으면 끝에 {@code SentinelImportException}({@code RUN_FAILED})을 던진다.
     */
    SentinelImportResult importSentinel(SentinelImportCommand command);
}
