package com.sneezecast.domainlayer.report.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주간 보고 도메인 오류 (coding-conventions §6-2 대역). 필드별 검증 코드(REPORT_101~)는 {@link ReportValidationMessage} 가 단일 기준점이며 여기서
 * 중복 정의하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode {

    // 같은 사람 · 같은 주 보고가 동시에 처리돼 이번 시도가 졌다 — 첫 보고 insert 가 uk_weekly_report_reporter_key_iso_week 에서 졌거나, 고치려던 행이
    // 그 사이 취소돼 없어졌다. WebFacade 가 새 트랜잭션으로 한 번 다시 부르므로 보통은 응답까지 오지 않는다. 다시 불러도 지면 이 코드가 그대로 나간다.
    CONCURRENT_SUBMISSION("REPORT_001", "같은 주 보고가 동시에 처리되었습니다. 잠시 후 다시 시도해주세요.", HttpStatus.CONFLICT),
    // 보고 행정동은 요청 값이라 리소스 부재(404)가 아니라 요청 오류(400)다 (entity-design §2-1).
    DISTRICT_NOT_FOUND("REPORT_002", "존재하지 않는 행정동입니다.", HttpStatus.BAD_REQUEST),
    // 개편으로 폐지된 코드. 화면이 동네를 다시 고르게 한다.
    DISTRICT_RETIRED("REPORT_003", "더 이상 쓰이지 않는 행정동입니다. 동네를 다시 골라주세요.", HttpStatus.BAD_REQUEST),

    // 요청 검증 대역 — 역직렬화 실패(알 수 없는 증상군 · 깨진 JSON) 폴백. 필드별 코드(REPORT_101~)는 ReportValidationMessage 에 있다.
    INVALID_REQUEST("REPORT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("REPORT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("REPORT_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
