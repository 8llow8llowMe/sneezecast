package com.sneezecast.domainlayer.report.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주간 보고 도메인 오류. 요청 검증 대역(REPORT_100 ~)과 필드별 코드는 API 를 여는 이슈(#143)에서 {@code ReportValidationMessage} 와 함께 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode {

    // 같은 사람 · 같은 주 첫 보고가 동시에 들어와 uk_weekly_report_reporter_key_iso_week 에서 한쪽이 졌다. WebFacade 가 새 트랜잭션에서 수정
    // 경로로 한 번 다시 부르므로 보통은 응답까지 오지 않는다. 다시 불러도 지면(그 사이 취소 등) 이 코드가 그대로 나간다.
    CONCURRENT_SUBMISSION("REPORT_001", "같은 주 보고가 동시에 처리되었습니다. 잠시 후 다시 시도해주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
