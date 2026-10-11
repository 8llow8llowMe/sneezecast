package com.sneezecast.domainlayer.advisory.application.exception;

/**
 * 예방 안내 요청 검증 메시지 카탈로그 (ADVISORY_1xx).
 *
 * <p>Bean Validation 의 {@code message} 는 컴파일 상수만 받아 enum 을 직접 쓸 수 없다. 형식은 {@code "코드:사용자 메시지"} —
 * {@code ValidationErrorSupport} 가 접두어를 분리한다.
 */
public final class AdvisoryValidationMessage {

    /** SGIS 읍면동 코드 형식 — 숫자 8자리 ({@code AggregateValidationMessage#DISTRICT_CODE_REGEXP} 와 같다). {@code \d} 는 ASCII 숫자만 받는다. */
    public static final String DISTRICT_CODE_REGEXP = "^\\d{8}$";

    /**
     * ISO 주 {@code YYYY-Www} — 주 번호는 01~53 만 받는다 ({@code AggregateValidationMessage#ISO_WEEK_REGEXP} 와 같다). 53주가 없는 해의 W53 은 형식으로
     * 거를 수 없어 조회 단계의 {@code ADVISORY_009} 다.
     */
    public static final String ISO_WEEK_REGEXP = "^\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])$";

    // 빈 문자열 · 공백은 형식이 잡는다 — 같은 입력에 두 오류가 겹쳐 나오지 않게 필수는 @NotNull 로만 본다 (공개 집계 지도 요청과 같다).
    public static final String DISTRICT_CODE_REQUIRED = "ADVISORY_101:행정동 코드를 입력해주세요.";
    public static final String DISTRICT_CODE_FORMAT_INVALID = "ADVISORY_102:행정동 코드는 숫자 8자리여야 합니다.";
    public static final String ISO_WEEK_REQUIRED = "ADVISORY_103:주를 입력해주세요. (예: 2026-W41)";
    public static final String ISO_WEEK_FORMAT_INVALID = "ADVISORY_104:주는 YYYY-Www 형식이어야 합니다. (예: 2026-W41)";

    private AdvisoryValidationMessage() {
    }
}
