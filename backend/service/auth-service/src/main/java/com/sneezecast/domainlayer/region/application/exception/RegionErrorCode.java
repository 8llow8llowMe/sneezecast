package com.sneezecast.domainlayer.region.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 내 동네 · 관심 동네(행정동) 오류. 회원 오류(MEMBER_)와 대역을 나눈다 — region 컨텍스트가 {@code member_region} ·
 * {@code member_interest_region} 을 소유한다 (modules.md).
 */
@Getter
@RequiredArgsConstructor
public enum RegionErrorCode {

    // 요청 코드가 surveillance 에 없다(DISTRICT_001). 동네 리소스가 아니라 요청 값의 문제라 404 가 아니라 400 이다 — 화면은 다시 고르게 한다.
    DISTRICT_NOT_FOUND("REGION_001", "존재하지 않는 행정동입니다. 다시 골라주세요.", HttpStatus.BAD_REQUEST),
    // 폐지된 코드(active=false)는 새로 저장할 수 없다 (entity-design §1-4 — 현행 코드만 저장한다).
    DISTRICT_ABOLISHED("REGION_002", "폐지된 행정동입니다. 다시 골라주세요.", HttpStatus.BAD_REQUEST),
    // 같은 회원의 첫 저장이 동시에 두 번 들어와 회원당 1행 unique 에 막혔다. 다시 보내면 갱신으로 풀린다.
    // 관심 동네도 쓴다 — 같은 회원의 추가가 동시에 들어와 같은 빈 칸(slot)을 노리다 막혔고 이 요청의 동네는 들어가지 않았다. 다시 보내면 다음 칸으로 풀린다.
    REGION_SAVE_CONFLICT("REGION_003", "동네 저장 요청이 겹쳤습니다. 잠시 후 다시 시도해주세요.", HttpStatus.CONFLICT),
    // surveillance 장애(5xx · timeout · 서킷 오픈). 검증하지 못한 코드는 저장하지 않는다 — 재시도 가능한 503 으로 알린다.
    INTERNAL_SERVICE_UNAVAILABLE("REGION_004", "일시적으로 행정동을 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    // 관심 동네가 이미 상한(region.interest.max-count)만큼 있다. 409 봉투에는 목록이 없다 — 화면은 GET 으로 목록을 다시 읽는다.
    INTEREST_REGION_LIMIT_EXCEEDED("REGION_005", "관심 동네를 더 고를 수 없습니다. 고른 동네를 지운 뒤 다시 골라주세요.", HttpStatus.CONFLICT),
    // 이미 고른 관심 동네다(다른 탭 · 기기에서 먼저 더했거나 같은 요청을 다시 보냈다). 화면은 GET 으로 목록을 다시 읽는다.
    INTEREST_REGION_ALREADY_SELECTED("REGION_006", "이미 고른 관심 동네입니다.", HttpStatus.CONFLICT),
    // 내 동네(member_region)와 같은 코드다. 내 동네를 나중에 관심 동네 중 하나로 바꾸는 것은 막지 않는다 — 그때 관심 동네는 그대로 둔다.
    INTEREST_REGION_SAME_AS_MY_REGION("REGION_007", "내 동네와 같은 동네입니다. 다른 동네를 골라주세요.", HttpStatus.CONFLICT),

    // 요청 검증 대역 — 필드별 코드(REGION_101~)는 RegionValidationMessage 가 단일 기준점이며 여기서 중복 정의하지 않는다.
    INVALID_REQUEST("REGION_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("REGION_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("REGION_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
