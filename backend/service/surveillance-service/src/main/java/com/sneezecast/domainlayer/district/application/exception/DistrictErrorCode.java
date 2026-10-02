package com.sneezecast.domainlayer.district.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DistrictErrorCode {

    // 리소스 자체의 부재라 404 다 (architecture-guide §8). 폐지된 코드는 여기에 해당하지 않는다 — 200 에 active=false 로 준다.
    DISTRICT_NOT_FOUND("DISTRICT_001", "존재하지 않는 행정동입니다.", HttpStatus.NOT_FOUND),

    // 요청 검증 대역 — 필드별 코드(DISTRICT_101~)는 DistrictValidationMessage 가 단일 기준점이며 여기서 중복 정의하지 않는다.
    INVALID_REQUEST("DISTRICT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("DISTRICT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("DISTRICT_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
