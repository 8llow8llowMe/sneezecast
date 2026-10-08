package com.sneezecast.domainlayer.member.application.port.in;

import com.sneezecast.domainlayer.member.application.model.ReportPurgeRunResult;

/**
 * 원시 보고 파기 스케줄 진입점 (entity-design §1-5). 웹이 아니라 스케줄러가 부르므로 {@code *WebUseCase} 가 아니다.
 */
public interface ReportPurgeUseCase {

    /** 부를 때가 된 미완료 파기 요청을 한 회차 처리한다. 항목 하나의 실패는 다음 항목을 막지 않고, 서킷이 열리면 회차를 멈춘다. */
    ReportPurgeRunResult purgeDue();

    /**
     * 보관 기간이 지난 완료 행을 지운다. 미완료 행은 지우지 않는다.
     *
     * @return 지운 행 수
     */
    int cleanUpCompleted();
}
