package com.sneezecast.domainlayer.advisory.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 예방 안내 도메인 오류 (coding-conventions §6-2 대역). 요청 검증 대역(ADVISORY_100 · 101~ · 198 · 199)과 advice 는 운영자 API(#246)가 컨트롤러와
 * 함께 더한다.
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
    ALREADY_PUBLISHED("ADVISORY_007", "이 동네 · 주에는 이미 발행 중인 안내가 있습니다. (행정동 %s, %s)", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
