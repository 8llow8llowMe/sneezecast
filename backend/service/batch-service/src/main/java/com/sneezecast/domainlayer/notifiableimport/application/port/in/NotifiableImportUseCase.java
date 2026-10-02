package com.sneezecast.domainlayer.notifiableimport.application.port.in;

import com.sneezecast.domainlayer.notifiableimport.application.command.NotifiableImportCommand;
import com.sneezecast.domainlayer.notifiableimport.application.model.NotifiableImportResult;

public interface NotifiableImportUseCase {

    /**
     * 올해 · 전년의 전수신고 주별 전국 · 시도 연별 값을 받아 공식 감시 자료로 적재한다. 요청마다 적재 이력을 남기고, 실패한 요청이 하나라도
     * 있으면 끝에 {@code NotifiableImportException}({@code RUN_FAILED})을 던진다.
     */
    NotifiableImportResult importNotifiable(NotifiableImportCommand command);
}
