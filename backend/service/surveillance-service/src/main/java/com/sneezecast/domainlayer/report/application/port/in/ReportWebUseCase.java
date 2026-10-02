package com.sneezecast.domainlayer.report.application.port.in;

import com.sneezecast.domainlayer.report.adapter.in.web.dto.response.WeeklyReportResponse;
import com.sneezecast.domainlayer.report.application.command.ReportSubmitCommand;

/**
 * 본인 이번 주 보고. 회원은 인증 주체의 {@code memberId} 로만 받고, 저장 · 조회에는 가명 키({@code reporter_key})만 쓴다. 보고 주는 서버가 정한다.
 */
public interface ReportWebUseCase {

    /**
     * 이번 주 보고를 저장한다 — 없으면 첫 보고, 있으면 수정. 같은 주 동시 제출은 한 번 다시 시도하고, 그래도 지면 {@code REPORT_001}(409).
     * 없는 · 폐지 행정동은 {@code REPORT_002} · {@code REPORT_003}(400).
     */
    WeeklyReportResponse submitCurrent(long memberId, ReportSubmitCommand command);

    /** 이번 주 보고. 아직 보고하지 않았으면 null 이다. */
    WeeklyReportResponse getCurrent(long memberId);

    /** 이번 주 보고를 취소(삭제)한다. 보고가 없어도 성공이다 (멱등). */
    void cancelCurrent(long memberId);
}
