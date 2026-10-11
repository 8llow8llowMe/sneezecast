package com.sneezecast.domainlayer.advisory.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 예방 안내 도메인 오류 (coding-conventions §6-2 대역). 필드별 검증 코드(ADVISORY_101~)는 {@link AdvisoryValidationMessage} 가 단일 기준점이며 여기서
 * 중복 정의하지 않는다. 요청 검증 대역 · advice 는 공개 동네 안내(#215)가 먼저 두었고, 운영자 API(#246)는 필드별 코드를 이어 붙인다.
 */
@Getter
@RequiredArgsConstructor
public enum AdvisoryErrorCode {

    ADVISORY_NOT_FOUND("ADVISORY_001", "존재하지 않는 안내입니다.", HttpStatus.NOT_FOUND),
    // 초안 근거 집계 — 그 동네 · 주에 집계 행이 없다(보고가 한 번도 없던 동).
    AGGREGATE_NOT_FOUND("ADVISORY_002", "근거 집계가 없습니다. (행정동 %s, %s)", HttpStatus.NOT_FOUND),
    // 초안 생성 전제(entity-design §2-3) — 마감된 집계로만 만든다. 주가 마감되면 다시 할 수 있다.
    AGGREGATE_NOT_FINALIZED("ADVISORY_003", "아직 마감되지 않은 주의 집계로는 안내를 만들 수 없습니다. (행정동 %s, %s)", HttpStatus.CONFLICT),
    // 초안 생성 전제 — 자료 부족(INSUFFICIENT) 집계로는 만들지 않는다. 마감된 집계는 바뀌지 않으므로 다시 해도 같다.
    AGGREGATE_INSUFFICIENT("ADVISORY_004", "자료가 부족한 집계로는 안내를 만들 수 없습니다. (행정동 %s, %s)", HttpStatus.CONFLICT),
    // §2-5 표에 없는 전이. 지금 상태 → 요청한 상태를 싣는다.
    INVALID_TRANSITION("ADVISORY_005", "지금 상태에서는 할 수 없는 처리입니다. (%s → %s)", HttpStatus.CONFLICT),
    // 요청이 실은 버전이 지금 버전과 다르거나, 같은 안내를 다른 운영자가 먼저 고쳤다(@Version 충돌). 화면은 다시 읽어 그린다.
    CONCURRENT_MODIFICATION("ADVISORY_006", "다른 운영자가 먼저 이 안내를 처리했습니다. 새로 불러온 뒤 다시 시도해주세요.", HttpStatus.CONFLICT),
    // 같은 동네 · 주에 이미 발행 중인 안내가 있다. 바꾸려면 그 안내를 철회(정정)한다.
    ALREADY_PUBLISHED("ADVISORY_007", "이 동네 · 주에는 이미 발행 중인 안내가 있습니다. (행정동 %s, %s)", HttpStatus.CONFLICT),
    // 공개 동네 안내 — 없는 행정동 코드. 폐지된 코드는 오류가 아니다(행정동 조회 #111 · 공개 집계 #212 와 같은 관례).
    DISTRICT_NOT_FOUND("ADVISORY_008", "존재하지 않는 행정동입니다.", HttpStatus.NOT_FOUND),
    // 공개 동네 안내 — 형식은 맞지만 조회할 수 없는 주(미래 주, 현재 주 ~ 51주 전 밖, 그 해에 없는 주). 공개 집계 AGGREGATE_002 와 같은 창이다.
    WEEK_NOT_QUERYABLE("ADVISORY_009", "조회할 수 없는 주입니다. (조회할 수 있는 주: %s ~ %s)", HttpStatus.BAD_REQUEST),
    // 운영자 초안 생성 — 형식(YYYY-Www, W01~W53)은 맞지만 표현할 수 없는 주(53주가 없는 해의 W53, 연도 1000 미만 — ReportWeek 가 받지 않는다). 형식은 ADVISORY_104 가 먼저 본다.
    WEEK_NOT_FOUND("ADVISORY_010", "없는 주입니다. (%s)", HttpStatus.BAD_REQUEST),

    // 요청 검증 대역 — 필드별 코드(ADVISORY_101~)는 AdvisoryValidationMessage 에 있다.
    INVALID_REQUEST("ADVISORY_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    // 프레임워크 2종은 대역 끝에 둔다 — 필드별 코드가 늘어도 번호가 끼어들지 않는다.
    PARAMETER_TYPE_INVALID("ADVISORY_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("ADVISORY_199", "필수 요청 파라미터가 누락되었습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
