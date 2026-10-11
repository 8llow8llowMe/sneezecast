package com.sneezecast.domainlayer.aggregate.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 공개 집계 조회 오류 (coding-conventions §6-2 대역). 필드별 검증 코드(AGGREGATE_101~)는 {@link AggregateValidationMessage} 가 단일 기준점이며 여기서
 * 중복 정의하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum AggregateErrorCode {

    // 행정동 조회(#111)와 같은 관례 — 없는 코드는 리소스 부재라 404, 폐지된 코드는 오류가 아니라 200 에 active=false 다. 지도 조회의 include 도 같다.
    DISTRICT_NOT_FOUND("AGGREGATE_001", "존재하지 않는 행정동입니다.", HttpStatus.NOT_FOUND),
    // 형식은 맞지만 조회할 수 없는 주 — 미래 주, 보관 창(현재 주 ~ 51주 전) 밖, 그 해에 없는 주(53주가 없는 해의 W53). 조회할 수 있는 범위를 싣는다.
    WEEK_NOT_QUERYABLE("AGGREGATE_002", "조회할 수 없는 주입니다. (조회할 수 있는 주: %s ~ %s)", HttpStatus.BAD_REQUEST),

    // 요청 검증 대역 — 필드별 코드(AGGREGATE_101~)는 AggregateValidationMessage 에 있다.
    INVALID_REQUEST("AGGREGATE_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("AGGREGATE_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("AGGREGATE_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
