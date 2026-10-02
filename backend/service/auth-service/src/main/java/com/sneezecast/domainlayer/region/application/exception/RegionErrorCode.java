package com.sneezecast.domainlayer.region.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 내 동네(행정동) 오류. 회원 오류(MEMBER_)와 대역을 나눈다 — region 컨텍스트가 {@code member_region} 을 소유한다 (modules.md).
 */
@Getter
@RequiredArgsConstructor
public enum RegionErrorCode {

    // 요청 코드가 surveillance 에 없다(DISTRICT_001). 동네 리소스가 아니라 요청 값의 문제라 404 가 아니라 400 이다 — 화면은 다시 고르게 한다.
    DISTRICT_NOT_FOUND("REGION_001", "존재하지 않는 행정동입니다. 다시 골라주세요.", HttpStatus.BAD_REQUEST),
    // 폐지된 코드(active=false)는 새로 저장할 수 없다 (entity-design §1-4 — 현행 코드만 저장한다).
    DISTRICT_ABOLISHED("REGION_002", "폐지된 행정동입니다. 다시 골라주세요.", HttpStatus.BAD_REQUEST),
    // 같은 회원의 첫 저장이 동시에 두 번 들어와 회원당 1행 unique 에 막혔다. 다시 보내면 갱신으로 풀린다.
    REGION_SAVE_CONFLICT("REGION_003", "동네 저장 요청이 겹쳤습니다. 잠시 후 다시 시도해주세요.", HttpStatus.CONFLICT),
    // surveillance 장애(5xx · timeout · 서킷 오픈). 검증하지 못한 코드는 저장하지 않는다 — 재시도 가능한 503 으로 알린다.
    INTERNAL_SERVICE_UNAVAILABLE("REGION_004", "일시적으로 행정동을 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // 요청 검증 대역 — 필드별 코드(REGION_101~)는 RegionValidationMessage 가 단일 기준점이며 여기서 중복 정의하지 않는다.
    INVALID_REQUEST("REGION_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("REGION_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("REGION_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
